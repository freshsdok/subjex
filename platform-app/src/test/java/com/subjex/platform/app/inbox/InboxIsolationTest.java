package com.subjex.platform.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.tenant.TenantIsolationTags;
import com.subjex.platform.contract.inbox.InboxChannel;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * InboxIsolationTest — IM-1 / TN-3: cross-tenant and cross-subject fail-closed on mailbox reads.
 * 跨租户与跨主体读信箱失败关闭。
 */
@Tag(TenantIsolationTags.TENANT_ISOLATION)
class InboxIsolationTest {

    private static final String TENANT_A = "tenant-inbox-iso-a";
    private static final String TENANT_B = "tenant-inbox-iso-b";
    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00Z");

    private JdbcInboxPort inbox;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        inbox = new JdbcInboxPort(jdbc, tx);
        ensureTenant(jdbc, TENANT_A);
        ensureTenant(jdbc, TENANT_B);
    }

    @Test
    void otherTenantCannotListOrMarkOrGet() {
        InboxSendResult sent = inbox.send(new InboxSendRequest(
                TENANT_A,
                InboxChannel.SYSTEM,
                "仅 A",
                "租户 A 通知",
                InboxSeverity.WARN,
                "system",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-shared-name")),
                T0));
        String entryId = sent.entries().get(0).entryId();

        assertThat(inbox.listEntries(
                        TENANT_B,
                        InboxRecipientRealm.OPERATOR,
                        "op-shared-name",
                        InboxListQuery.defaults()))
                .isEmpty();
        assertThat(inbox.unreadCount(TENANT_B, InboxRecipientRealm.OPERATOR, "op-shared-name"))
                .isZero();
        assertThat(inbox.getEntry(TENANT_B, InboxRecipientRealm.OPERATOR, "op-shared-name", entryId))
                .isEmpty();
        assertThat(inbox.markRead(
                        TENANT_B, InboxRecipientRealm.OPERATOR, "op-shared-name", entryId, T0.plusSeconds(1)))
                .isFalse();
        // Original still unread in A — A 侧仍未读
        assertThat(inbox.unreadCount(TENANT_A, InboxRecipientRealm.OPERATOR, "op-shared-name")).isEqualTo(1);
    }

    @Test
    void sameTenantOtherSubjectCannotRead() {
        InboxSendResult sent = inbox.send(new InboxSendRequest(
                TENANT_A,
                InboxChannel.WORKFLOW,
                "给 Alice",
                "审批提醒",
                InboxSeverity.ACTION,
                "workflow",
                "repair-ticket",
                null,
                null,
                null,
                null,
                null,
                List.of(new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-alice")),
                T0));
        String entryId = sent.entries().get(0).entryId();

        assertThat(inbox.listEntries(
                        TENANT_A, InboxRecipientRealm.OPERATOR, "op-bob", InboxListQuery.defaults()))
                .isEmpty();
        assertThat(inbox.getEntry(TENANT_A, InboxRecipientRealm.OPERATOR, "op-bob", entryId)).isEmpty();
        assertThat(inbox.markRead(
                        TENANT_A, InboxRecipientRealm.OPERATOR, "op-bob", entryId, T0.plusSeconds(1)))
                .isFalse();
        assertThat(inbox.unreadCount(TENANT_A, InboxRecipientRealm.OPERATOR, "op-alice")).isEqualTo(1);
    }

    @Test
    void realmsDoNotCrossList() {
        inbox.send(new InboxSendRequest(
                TENANT_A,
                InboxChannel.SYSTEM,
                "操作员信",
                "仅 OPERATOR",
                InboxSeverity.INFO,
                "system",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new InboxRecipient(InboxRecipientRealm.OPERATOR, "same-subject")),
                T0));
        assertThat(inbox.listEntries(
                        TENANT_A, InboxRecipientRealm.END_USER, "same-subject", InboxListQuery.defaults()))
                .isEmpty();
        assertThat(inbox.unreadCount(TENANT_A, InboxRecipientRealm.END_USER, "same-subject")).isZero();
    }

    private static void ensureTenant(JdbcTemplate jdbc, String tenantId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, tenantId);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    tenantId,
                    tenantId);
        }
    }
}
