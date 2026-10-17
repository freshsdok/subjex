package com.subjex.platform.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxFanoutStatus;
import com.subjex.platform.contract.inbox.InboxRecipient;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.inbox.InboxSendRequest;
import com.subjex.platform.contract.inbox.InboxSendResult;
import com.subjex.platform.contract.inbox.InboxSeverity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * InboxFanoutJobTest — IM-A6: deferred send + batch processFanoutBatch lands inbox_entry.
 * 异步写扩散：暂存任务 + 批次写入 inbox_entry。
 */
class InboxFanoutJobTest {

    private static final String TENANT = "tenant-inbox-a6";
    private static final Instant T0 = Instant.parse("2026-10-17T15:00:00Z");

    private JdbcInboxPort inbox;
    private JdbcTemplate jdbc;
    private InboxFanoutWorker worker;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        inbox = new JdbcInboxPort(jdbc, tx);
        worker = new InboxFanoutWorker(inbox, Runnable::run);
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, TENANT);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT,
                    TENANT);
        }
    }

    @Test
    void sendDeferredThenBatchWritesInboxEntries() {
        List<InboxRecipient> recipients = recipients(InboxSendRequest.SYNC_FANOUT_MAX + 5);
        InboxSendResult deferred = inbox.sendDeferred(groupSend(recipients, "异步消息"));
        assertThat(deferred.fanoutStatus()).isEqualTo(InboxFanoutStatus.PENDING);
        assertThat(deferred.fanoutJobId()).isNotBlank();
        assertThat(deferred.entries()).isEmpty();

        Integer staged = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_fanout_recipient WHERE job_id = ?",
                Integer.class,
                deferred.fanoutJobId());
        assertThat(staged).isEqualTo(recipients.size());
        assertThat(entryCount(deferred.message().messageId())).isZero();

        int first = inbox.processFanoutBatch(deferred.fanoutJobId(), 50);
        assertThat(first).isEqualTo(50);
        assertThat(entryCount(deferred.message().messageId())).isEqualTo(50);

        worker.drain(deferred.fanoutJobId());
        assertThat(entryCount(deferred.message().messageId())).isEqualTo(recipients.size());
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM inbox_fanout_job WHERE job_id = ?",
                        String.class,
                        deferred.fanoutJobId()))
                .isEqualTo("DONE");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM inbox_fanout_recipient WHERE job_id = ?",
                        Integer.class,
                        deferred.fanoutJobId()))
                .isZero();
    }

    @Test
    void workerScheduleDrainsAfterDeferred() {
        List<InboxRecipient> recipients = recipients(InboxSendRequest.SYNC_FANOUT_MAX + 1);
        InboxSendResult deferred = inbox.sendDeferred(groupSend(recipients, "调度排空"));
        worker.schedule(deferred.fanoutJobId());
        assertThat(entryCount(deferred.message().messageId())).isEqualTo(recipients.size());
    }

    @Test
    void sendDeferredRejectsAtOrBelowSyncAndAboveHard() {
        assertThatThrownBy(() -> inbox.sendDeferred(groupSend(recipients(10), "太小")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("syncFanoutMax");

        assertThatThrownBy(
                        () -> inbox.sendDeferred(groupSend(recipients(InboxSendRequest.HARD_FANOUT_MAX + 1), "过大")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hardFanoutMax");
    }

    @Test
    void syncSendStillRejectsAboveSyncMax() {
        assertThatThrownBy(() -> inbox.send(groupSend(recipients(InboxSendRequest.SYNC_FANOUT_MAX + 1), "同步过大")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("syncFanoutMax");
    }

    private int entryCount(String messageId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_entry WHERE message_id = ? AND tenant_id = ?",
                Integer.class,
                messageId,
                TENANT);
        return n == null ? 0 : n;
    }

    private static List<InboxRecipient> recipients(int n) {
        List<InboxRecipient> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-a6-" + i));
        }
        return list;
    }

    private static InboxSendRequest groupSend(List<InboxRecipient> recipients, String title) {
        return new InboxSendRequest(
                TENANT,
                InboxChannel.GROUP_SEND,
                title,
                "body",
                InboxSeverity.INFO,
                "group",
                "op-admin",
                "inbox_group",
                "g1",
                null,
                null,
                null,
                recipients,
                T0);
    }
}
