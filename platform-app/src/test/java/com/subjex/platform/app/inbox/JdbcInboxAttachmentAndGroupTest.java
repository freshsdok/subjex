package com.subjex.platform.app.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.inbox.InboxAttachment;
import com.subjex.platform.contract.inbox.InboxAttachmentBind;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxGroup;
import com.subjex.platform.contract.inbox.InboxRecipient;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.inbox.InboxSendRequest;
import com.subjex.platform.contract.inbox.InboxSendResult;
import com.subjex.platform.contract.inbox.InboxSeverity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcInboxAttachmentAndGroupTest — IM-A1: attachments bind + own-entry download + groups.
 * 附件绑定、本人下载隔离与收件组。
 */
class JdbcInboxAttachmentAndGroupTest {

    private static final String TENANT = "tenant-inbox-a1";
    private static final Instant T0 = Instant.parse("2026-10-17T12:00:00Z");

    private JdbcInboxPort inbox;
    private JdbcInboxGroupPort groups;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        inbox = new JdbcInboxPort(jdbc, tx);
        groups = new JdbcInboxGroupPort(jdbc, tx);
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, TENANT);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT,
                    TENANT);
        }
    }

    @Test
    void sendBindsAttachmentsAndOwnEntryCanList() {
        InboxAttachmentBind bind = new InboxAttachmentBind(
                "notice.pdf",
                "application/pdf",
                128,
                "inbox/staging/notice.pdf",
                "a".repeat(64),
                0);
        InboxSendResult sent = inbox.send(new InboxSendRequest(
                TENANT,
                InboxChannel.SYSTEM,
                "带附件",
                "请查收附件",
                InboxSeverity.INFO,
                "system",
                "admin",
                null,
                null,
                null,
                null,
                null,
                List.of(
                        new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-alice"),
                        new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-bob")),
                List.of(bind),
                T0));
        assertThat(sent.attachments()).hasSize(1);
        assertThat(sent.attachments().get(0).fileName()).isEqualTo("notice.pdf");

        String aliceEntry = sent.entries().stream()
                .filter(e -> e.recipientSubjectId().equals("op-alice"))
                .findFirst()
                .orElseThrow()
                .entryId();
        List<InboxAttachment> listed = inbox.listAttachmentsForOwnEntry(
                TENANT, InboxRecipientRealm.OPERATOR, "op-alice", aliceEntry);
        assertThat(listed).hasSize(1);
        Optional<InboxAttachment> one = inbox.findAttachmentForOwnEntry(
                TENANT,
                InboxRecipientRealm.OPERATOR,
                "op-alice",
                aliceEntry,
                listed.get(0).attachmentId());
        assertThat(one).isPresent();
        assertThat(one.get().storageKey()).isEqualTo("inbox/staging/notice.pdf");
    }

    @Test
    void strangerCannotListAttachmentsForOthersEntry() {
        InboxSendResult sent = inbox.send(new InboxSendRequest(
                TENANT,
                InboxChannel.SYSTEM,
                "私密",
                "仅 alice",
                InboxSeverity.INFO,
                "system",
                "admin",
                null,
                null,
                null,
                null,
                null,
                List.of(new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-alice")),
                List.of(new InboxAttachmentBind(
                        "secret.txt", "text/plain", 4, "inbox/secret.txt", "b".repeat(64), 0)),
                T0));
        String aliceEntry = sent.entries().get(0).entryId();
        assertThat(inbox.listAttachmentsForOwnEntry(
                        TENANT, InboxRecipientRealm.OPERATOR, "op-eve", aliceEntry))
                .isEmpty();
        assertThat(inbox.findAttachmentForOwnEntry(
                        TENANT,
                        InboxRecipientRealm.OPERATOR,
                        "op-eve",
                        aliceEntry,
                        sent.attachments().get(0).attachmentId()))
                .isEmpty();
    }

    @Test
    void rejectsSyncFanoutAboveBound() {
        List<InboxRecipient> many = new ArrayList<>();
        for (int i = 0; i < InboxSendRequest.SYNC_FANOUT_MAX + 1; i++) {
            many.add(new InboxRecipient(InboxRecipientRealm.OPERATOR, "op-" + i));
        }
        assertThatThrownBy(() -> inbox.send(new InboxSendRequest(
                        TENANT,
                        InboxChannel.SYSTEM,
                        "过大",
                        "应拒绝",
                        InboxSeverity.INFO,
                        "system",
                        "admin",
                        null,
                        null,
                        null,
                        null,
                        null,
                        many,
                        T0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("syncFanoutMax");
    }

    @Test
    void groupCrudMembersExpandAndTenantIsolation() {
        InboxGroup g = groups.create(
                TENANT, "值班组", "夜班", InboxRecipientRealm.OPERATOR, "op-admin", T0);
        assertThat(g.name()).isEqualTo("值班组");
        assertThat(groups.addMember(
                        TENANT, g.groupId(), InboxRecipientRealm.OPERATOR, "op-a", T0.plusSeconds(1)))
                .isTrue();
        assertThat(groups.addMember(
                        TENANT, g.groupId(), InboxRecipientRealm.OPERATOR, "op-b", T0.plusSeconds(2)))
                .isTrue();
        assertThat(groups.addMember(
                        TENANT, g.groupId(), InboxRecipientRealm.OPERATOR, "op-a", T0.plusSeconds(3)))
                .isTrue(); // idempotent
        assertThat(groups.listMembers(TENANT, g.groupId())).hasSize(2);
        assertThat(groups.expandMembers(TENANT, g.groupId()))
                .extracting(InboxRecipient::subjectId)
                .containsExactlyInAnyOrder("op-a", "op-b");

        assertThat(groups.get("other-tenant", g.groupId())).isEmpty();
        assertThat(groups.listMembers("other-tenant", g.groupId())).isEmpty();

        assertThat(groups.update(TENANT, g.groupId(), "值班组-改", null, T0.plusSeconds(10))).isTrue();
        assertThat(groups.get(TENANT, g.groupId())).isPresent().get().extracting(InboxGroup::name)
                .isEqualTo("值班组-改");
        assertThat(groups.removeMember(TENANT, g.groupId(), InboxRecipientRealm.OPERATOR, "op-b")).isTrue();
        assertThat(groups.expandMembers(TENANT, g.groupId())).hasSize(1);
        assertThat(groups.delete(TENANT, g.groupId())).isTrue();
        assertThat(groups.get(TENANT, g.groupId())).isEmpty();
        Integer members = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_group_member WHERE group_id = ?", Integer.class, g.groupId());
        assertThat(members).isZero();
    }

    @Test
    void permissionsCatalogIncludesBroadcastAndGroupAdmin() {
        Integer broadcast = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_permission WHERE permission_name = 'inbox.broadcast'",
                Integer.class);
        Integer groupAdmin = jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_permission WHERE permission_name = 'inbox.group.admin'",
                Integer.class);
        assertThat(broadcast).isEqualTo(1);
        assertThat(groupAdmin).isEqualTo(1);
    }
}
