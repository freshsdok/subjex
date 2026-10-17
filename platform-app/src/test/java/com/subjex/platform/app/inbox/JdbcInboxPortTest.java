package com.subjex.platform.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxEntryView;
import com.subjex.platform.contract.inbox.InboxListQuery;
import com.subjex.platform.contract.inbox.InboxRecipient;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.inbox.InboxSendRequest;
import com.subjex.platform.contract.inbox.InboxSendResult;
import com.subjex.platform.contract.inbox.InboxSeverity;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcInboxPortTest — IM-1: send / list / unread / mark-read on H2 Flyway schema.
 * 在 H2 Flyway schema 上验证发送/列表/未读/已读。
 */
class JdbcInboxPortTest {

    private static final String TENANT = "tenant-inbox-a";
    private static final Instant T0 = Instant.parse("2026-10-17T09:00:00Z");

    private JdbcInboxPort inbox;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        inbox = new JdbcInboxPort(jdbc, tx);
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, TENANT);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT,
                    TENANT);
        }
    }

    @Test
    void sendFansOutAndListUnreadMarkRead() {
        InboxSendResult sent = inbox.send(systemTo(
                List.of(
                        new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-alice"),
                        new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-bob")),
                "维护窗口",
                "今晚 22:00 短暂维护",
                T0));
        assertThat(sent.message().messageId()).isNotBlank();
        assertThat(sent.entries()).hasSize(2);
        assertThat(inbox.unreadCount(TENANT, InboxRecipientRealm.OPERATOR, "op-alice")).isEqualTo(1);
        assertThat(inbox.unreadCount(TENANT, InboxRecipientRealm.OPERATOR, "op-bob")).isEqualTo(1);

        List<InboxEntryView> alice = inbox.listEntries(
                TENANT, InboxRecipientRealm.OPERATOR, "op-alice", InboxListQuery.defaults());
        assertThat(alice).hasSize(1);
        assertThat(alice.get(0).message().title()).isEqualTo("维护窗口");
        assertThat(alice.get(0).entry().unread()).isTrue();

        String entryId = alice.get(0).entry().entryId();
        assertThat(inbox.markRead(
                        TENANT, InboxRecipientRealm.OPERATOR, "op-alice", entryId, T0.plusSeconds(60)))
                .isTrue();
        assertThat(inbox.unreadCount(TENANT, InboxRecipientRealm.OPERATOR, "op-alice")).isZero();
        assertThat(inbox.markRead(
                        TENANT, InboxRecipientRealm.OPERATOR, "op-alice", entryId, T0.plusSeconds(120)))
                .isTrue(); // idempotent

        assertThat(inbox.getEntry(TENANT, InboxRecipientRealm.OPERATOR, "op-alice", entryId))
                .isPresent()
                .get()
                .extracting(v -> v.entry().readAt())
                .isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void unreadOnlyAndChannelFilter() {
        inbox.send(systemTo(
                List.of(new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-carol")),
                "系统",
                "系统正文",
                T0));
        inbox.send(new InboxSendRequest(
                TENANT,
                InboxChannel.WORKFLOW,
                "待审批",
                "请审批报修单",
                InboxSeverity.ACTION,
                "workflow",
                "repair-ticket",
                "workflow_instance",
                "inst-1",
                "repair-ticket",
                3,
                "/pages/repair-ticket?id=1",
                List.of(new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-carol")),
                T0.plusSeconds(1)));

        assertThat(inbox.listEntries(
                        TENANT,
                        InboxRecipientRealm.OPERATOR,
                        "op-carol",
                        new InboxListQuery(true, null, 50)))
                .hasSize(2);
        assertThat(inbox.listEntries(
                        TENANT,
                        InboxRecipientRealm.OPERATOR,
                        "op-carol",
                        new InboxListQuery(false, InboxChannel.WORKFLOW, 50)))
                .hasSize(1)
                .first()
                .extracting(v -> v.message().channel())
                .isEqualTo(InboxChannel.WORKFLOW);
    }

    @Test
    void dedupeesSameRecipientOnSend() {
        InboxSendResult sent = inbox.send(systemTo(
                List.of(
                        new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-dup"),
                        new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-dup")),
                "去重",
                "只应一条",
                T0));
        assertThat(sent.entries()).hasSize(1);
    }

    @Test
    void refusesBlankTenantOrEmptyRecipients() {
        assertThatThrownBy(() -> inbox.send(new InboxSendRequest(
                        "  ",
                        InboxChannel.SYSTEM,
                        "t",
                        "b",
                        InboxSeverity.INFO,
                        "system",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(new InboxRecipient(InboxRecipientRealm.OPERATOR, "x")),
                        T0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InboxSendRequest(
                        TENANT,
                        InboxChannel.SYSTEM,
                        "t",
                        "b",
                        InboxSeverity.INFO,
                        "system",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static InboxSendRequest systemTo(
            List<InboxRecipient> recipients, String title, String body, Instant at) {
        return new InboxSendRequest(
                TENANT,
                InboxChannel.SYSTEM,
                title,
                body,
                InboxSeverity.INFO,
                "system",
                "admin",
                null,
                null,
                null,
                null,
                null,
                recipients,
                at);
    }
}
