package com.subjex.platform.app.inbox;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.entity.ChunkedBlobUploadStore;
import com.subjex.platform.app.entity.LocalDirChunkedBlobUploadStore;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.app.tenant.TenantIsolationTags;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.organization.OrganizationScopeResolver;
import com.subjex.platform.contract.inbox.InboxGroupPort;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.inbox.InboxSendRequest;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * InboxGroupSendApiTest — IM-A4: GROUP_SEND sync fan-out + broadcast AuthZ + caps.
 * 组群发同步写扩散、broadcast 权限与上限。
 */
@WebMvcTest(controllers = {InboxEndpoint.class, InboxGroupEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    InboxGroupSendApiTest.Config.class
})
@Tag(TenantIsolationTags.TENANT_ISOLATION)
class InboxGroupSendApiTest {

    private static final String TENANT = "tenant-inbox-a4";
    private static final String VIEWER_SUBJECT = "subject-viewer";
    private static final Instant T0 = Instant.parse("2026-10-17T13:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OperatorTenantAccess tenantAccess;

    @Autowired
    private InboxGroupPort groups;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM inbox_entry WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM inbox_attachment WHERE tenant_id = ?", TENANT);
        jdbc.update(
                "DELETE FROM inbox_fanout_recipient WHERE job_id IN (SELECT job_id FROM inbox_fanout_job WHERE tenant_id = ?)",
                TENANT);
        jdbc.update("DELETE FROM inbox_fanout_job WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM inbox_message WHERE tenant_id = ?", TENANT);
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, TENANT);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT,
                    TENANT);
        }
        tenantAccess.ensureGrant(LocalOperatorSeeder.SUBJECT_ID, OperatorTenantAccess.ALL_TENANTS);
        tenantAccess.ensureGrant(VIEWER_SUBJECT, TENANT);
    }

    @Test
    void groupSendFansOutToMembersWithOptionalAttachment() throws Exception {
        String groupId = groups.create(
                        TENANT, "发布组", null, InboxRecipientRealm.OPERATOR, LocalOperatorSeeder.SUBJECT_ID, T0)
                .groupId();
        groups.addMember(TENANT, groupId, InboxRecipientRealm.OPERATOR, LocalOperatorSeeder.SUBJECT_ID, T0);
        groups.addMember(TENANT, groupId, InboxRecipientRealm.OPERATOR, VIEWER_SUBJECT, T0.plusSeconds(1));

        byte[] bytes = "group-note".getBytes(StandardCharsets.UTF_8);
        MvcResult staged = mockMvc.perform(post(InboxEndpoint.BASE + "/attachments")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .param("fileName", "note.txt")
                        .param("contentType", "text/plain")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bytes)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String stagingId =
                objectMapper.readTree(staged.getResponse().getContentAsString()).get("stagingId").asText();

        mockMvc.perform(post(InboxEndpoint.BASE + "/group-send")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "groupId": "%s",
                                  "title": "组通知",
                                  "body": "请查收",
                                  "severity": "INFO",
                                  "attachmentStagingIds": ["%s"]
                                }
                                """.formatted(groupId, stagingId))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("GROUP_SEND"))
                .andExpect(jsonPath("$.fanoutStatus").value("COMPLETE"))
                .andExpect(jsonPath("$.entryIds", hasSize(2)))
                .andExpect(jsonPath("$.attachmentIds", hasSize(1)));

        mockMvc.perform(get(InboxEndpoint.BASE + "/entries")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .param("channel", "GROUP_SEND")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].title").value("组通知"));
    }

    @Test
    void emptyGroupRejectedAndMissingGroupNotFound() throws Exception {
        String emptyId = groups.create(
                        TENANT, "空组", null, InboxRecipientRealm.OPERATOR, LocalOperatorSeeder.SUBJECT_ID, T0)
                .groupId();
        mockMvc.perform(post(InboxEndpoint.BASE + "/group-send")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupId":"%s","title":"x","body":"y"}
                                """.formatted(emptyId))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(InboxEndpoint.BASE + "/group-send")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupId":"missing-group","title":"x","body":"y"}
                                """)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    void midRangeMembersUseAsyncFanoutAndLandInInbox() throws Exception {
        String groupId = groups.create(
                        TENANT, "中等组", null, InboxRecipientRealm.OPERATOR, LocalOperatorSeeder.SUBJECT_ID, T0)
                .groupId();
        int n = InboxSendRequest.SYNC_FANOUT_MAX + 1;
        for (int i = 0; i < n; i++) {
            groups.addMember(
                    TENANT, groupId, InboxRecipientRealm.OPERATOR, "op-mid-" + i, T0.plusSeconds(i));
        }
        mockMvc.perform(post(InboxEndpoint.BASE + "/group-send")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupId":"%s","title":"异步组发","body":"应排队"}
                                """.formatted(groupId))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("GROUP_SEND"))
                .andExpect(jsonPath("$.fanoutStatus").value("PENDING"))
                .andExpect(jsonPath("$.fanoutJobId").isNotEmpty())
                .andExpect(jsonPath("$.entryIds", hasSize(0)));

        Integer entries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_entry e JOIN inbox_message m ON m.message_id = e.message_id WHERE m.tenant_id = ? AND m.title = ?",
                Integer.class,
                TENANT,
                "异步组发");
        org.assertj.core.api.Assertions.assertThat(entries).isEqualTo(n);

        Integer pendingRecipients = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_fanout_recipient r JOIN inbox_fanout_job j ON j.job_id = r.job_id WHERE j.tenant_id = ?",
                Integer.class,
                TENANT);
        org.assertj.core.api.Assertions.assertThat(pendingRecipients).isZero();
    }

    @Test
    void rejectsWhenMembersExceedHardFanoutMax() throws Exception {
        String groupId = groups.create(
                        TENANT, "过大组", null, InboxRecipientRealm.OPERATOR, LocalOperatorSeeder.SUBJECT_ID, T0)
                .groupId();
        for (int i = 0; i < InboxSendRequest.HARD_FANOUT_MAX + 1; i++) {
            groups.addMember(
                    TENANT, groupId, InboxRecipientRealm.OPERATOR, "op-hard-" + i, T0.plusSeconds(i));
        }
        mockMvc.perform(post(InboxEndpoint.BASE + "/group-send")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupId":"%s","title":"过大","body":"应拒绝"}
                                """.formatted(groupId))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void viewerWithoutBroadcastIsForbidden() throws Exception {
        String groupId = groups.create(
                        TENANT, "禁组", null, InboxRecipientRealm.OPERATOR, LocalOperatorSeeder.SUBJECT_ID, T0)
                .groupId();
        groups.addMember(TENANT, groupId, InboxRecipientRealm.OPERATOR, VIEWER_SUBJECT, T0);
        mockMvc.perform(post(InboxEndpoint.BASE + "/group-send")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupId":"%s","title":"x","body":"y"}
                                """.formatted(groupId))
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantGroupSendNotFound() throws Exception {
        String other = "tenant-inbox-a4-other";
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, other);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    other,
                    other);
        }
        String groupId = groups.create(
                        TENANT, "本租户组", null, InboxRecipientRealm.OPERATOR, LocalOperatorSeeder.SUBJECT_ID, T0)
                .groupId();
        groups.addMember(TENANT, groupId, InboxRecipientRealm.OPERATOR, VIEWER_SUBJECT, T0);
        mockMvc.perform(post(InboxEndpoint.BASE + "/group-send")
                        .header(TenantEnforcementFilter.TENANT_HEADER, other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupId":"%s","title":"x","body":"y"}
                                """.formatted(groupId))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration
    static class Config {

        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        TransactionTemplate transactionTemplate(DataSource operatorTables) {
            return new TransactionTemplate(new DataSourceTransactionManager(operatorTables));
        }

        @Bean
        InboxPort inboxPort(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
            return new JdbcInboxPort(jdbc, transactionTemplate);
        }

        @Bean
        InboxGroupPort inboxGroupPort(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
            return new JdbcInboxGroupPort(jdbc, transactionTemplate);
        }

        @Bean
        JdbcOrganizationStore jdbcOrganizationStore(JdbcTemplate jdbc) {
            return new JdbcOrganizationStore(jdbc);
        }

        @Bean
        OrganizationScopeResolver organizationScopeResolver(JdbcOrganizationStore jdbcOrganizationStore) {
            return new OrganizationScopeResolver(jdbcOrganizationStore);
        }

        @Bean
        InboxOrgBroadcastExpander inboxOrgBroadcastExpander(
                JdbcOrganizationStore jdbcOrganizationStore, OrganizationScopeResolver organizationScopeResolver) {
            return new InboxOrgBroadcastExpander(jdbcOrganizationStore, organizationScopeResolver);
        }

        @Bean
        ObjectStorage objectStorage() throws Exception {
            return new LocalDirectoryObjectStorage(Files.createTempDirectory("inbox-a4-objects"));
        }

        @Bean
        ChunkedBlobUploadStore chunkedBlobUploadStore() throws Exception {
            return new LocalDirChunkedBlobUploadStore(
                    Files.createTempDirectory("inbox-a4-chunks"), Clock.systemUTC());
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        InboxAttachmentStagingStore inboxAttachmentStagingStore(Clock clock, ObjectStorage objectStorage) {
            return new InboxAttachmentStagingStore(clock, objectStorage);
        }

        @Bean
        InboxFanoutWorker inboxFanoutWorker(InboxPort inboxPort) {
            return new InboxFanoutWorker(inboxPort, Runnable::run);
        }

        @Bean
        InboxService inboxService(
                InboxPort inboxPort,
                InboxGroupPort inboxGroupPort,
                InboxOrgBroadcastExpander inboxOrgBroadcastExpander,
                OperatorTenantAccess tenantAccess,
                TenantGuard tenantGuard,
                Clock clock,
                ObjectStorage objectStorage,
                ChunkedBlobUploadStore chunkedBlobUploadStore,
                InboxAttachmentStagingStore staging,
                InboxFanoutWorker inboxFanoutWorker) {
            return new InboxService(
                    inboxPort,
                    inboxGroupPort,
                    inboxOrgBroadcastExpander,
                    tenantAccess,
                    tenantGuard,
                    clock,
                    objectStorage,
                    chunkedBlobUploadStore,
                    staging,
                    inboxFanoutWorker);
        }

        @Bean
        InboxGroupService inboxGroupService(
                InboxGroupPort inboxGroupPort, OperatorTenantAccess tenantAccess, TenantGuard tenantGuard, Clock clock) {
            return new InboxGroupService(inboxGroupPort, tenantAccess, tenantGuard, clock);
        }
    }
}
