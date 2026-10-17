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
import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.organization.OrganizationScopeResolver;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.app.tenant.TenantIsolationTags;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.inbox.InboxGroupPort;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.inbox.InboxSendRequest;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
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
 * InboxOrgBroadcastApiTest — IM-A5: ORG_BROADCAST OrgScope ∩ membership + sync caps.
 * 组织广播：OrgScope∩成员、同步上限与门禁。
 */
@WebMvcTest(controllers = InboxEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    InboxOrgBroadcastApiTest.Config.class
})
@Tag(TenantIsolationTags.TENANT_ISOLATION)
class InboxOrgBroadcastApiTest {

    private static final String TENANT = "tenant-inbox-a5";
    private static final String VIEWER_SUBJECT = "subject-viewer";
    private static final String OP = LocalOperatorSeeder.SUBJECT_ID;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OperatorTenantAccess tenantAccess;

    @Autowired
    private JdbcOrganizationStore orgs;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        cleanupBulkMembers();
        cleanupInbox();
        ensureTenant(TENANT);
        tenantAccess.ensureGrant(OP, OperatorTenantAccess.ALL_TENANTS);
        tenantAccess.ensureGrant(VIEWER_SUBJECT, TENANT);
        orgs.upsertOrganization("org-root", "Root", "ACTIVE");
        orgs.upsertOrganization("org-eng", "Eng", "ACTIVE");
        orgs.upsertOrganization("org-team", "Team", "ACTIVE");
        orgs.upsertOrganization("org-sales", "Sales", "ACTIVE");
        orgs.upsertTenantOrganization(TENANT, "org-root", "ACTIVE");
        orgs.upsertTenantOrganization(TENANT, "org-eng", "ACTIVE");
        orgs.upsertTenantOrganization(TENANT, "org-team", "ACTIVE");
        orgs.upsertTenantOrganization(TENANT, "org-sales", "ACTIVE");
        orgs.setContainsParent("org-eng", "org-root");
        orgs.setContainsParent("org-team", "org-eng");
        orgs.setContainsParent("org-sales", "org-root");
        ensureSubject(OP, "Operator");
        ensureSubject("mem-eng-a", "Eng A");
        ensureSubject("mem-team-b", "Team B");
        ensureSubject("mem-sales-c", "Sales C");
        // Operator scoped to eng (+ team descendants); not sales.
        orgs.upsertMembership(OP, "org-eng", "ACTIVE");
        orgs.upsertMembership("mem-eng-a", "org-eng", "ACTIVE");
        orgs.upsertMembership("mem-team-b", "org-team", "ACTIVE");
        orgs.upsertMembership("mem-sales-c", "org-sales", "ACTIVE");
    }

    private void ensureSubject(String subjectId, String name) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM subject WHERE subject_id = ?", Integer.class, subjectId);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                    subjectId,
                    name,
                    "PERSON");
        }
    }

    @Test
    void broadcastWithDescendantsAndAttachment() throws Exception {
        byte[] bytes = "org-note".getBytes(StandardCharsets.UTF_8);
        MvcResult staged = mockMvc.perform(post(InboxEndpoint.BASE + "/attachments")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .param("fileName", "org.txt")
                        .param("contentType", "text/plain")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bytes)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String stagingId =
                objectMapper.readTree(staged.getResponse().getContentAsString()).get("stagingId").asText();

        mockMvc.perform(post(InboxEndpoint.BASE + "/broadcast")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgIds": ["org-eng"],
                                  "includeDescendants": true,
                                  "title": "工程通知",
                                  "body": "请查收",
                                  "attachmentStagingIds": ["%s"]
                                }
                                """.formatted(stagingId))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("ORG_BROADCAST"))
                .andExpect(jsonPath("$.fanoutStatus").value("COMPLETE"))
                // OP + mem-eng-a + mem-team-b (sales excluded)
                .andExpect(jsonPath("$.entryIds", hasSize(3)))
                .andExpect(jsonPath("$.attachmentIds", hasSize(1)));

        mockMvc.perform(get(InboxEndpoint.BASE + "/entries")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .param("channel", "ORG_BROADCAST")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].title").value("工程通知"));
    }

    @Test
    void outOfScopeOrgForbiddenAndUnknownNotFound() throws Exception {
        mockMvc.perform(post(InboxEndpoint.BASE + "/broadcast")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orgIds":["org-sales"],"title":"x","body":"y"}
                                """)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(InboxEndpoint.BASE + "/broadcast")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orgIds":["org-missing"],"title":"x","body":"y"}
                                """)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    void emptyMembershipsRejected() throws Exception {
        orgs.upsertOrganization("org-empty", "Empty", "ACTIVE");
        orgs.upsertTenantOrganization(TENANT, "org-empty", "ACTIVE");
        orgs.setContainsParent("org-empty", "org-eng");
        // in scope via eng descendants, but no members
        mockMvc.perform(post(InboxEndpoint.BASE + "/broadcast")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orgIds":["org-empty"],"includeDescendants":false,"title":"x","body":"y"}
                                """)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void midRangeMembersUseAsyncFanoutAndLandInInbox() throws Exception {
        int n = InboxSendRequest.SYNC_FANOUT_MAX + 1;
        for (int i = 0; i < n; i++) {
            String sid = "bulk-" + i;
            ensureSubject(sid, sid);
            orgs.upsertMembership(sid, "org-eng", "ACTIVE");
        }
        mockMvc.perform(post(InboxEndpoint.BASE + "/broadcast")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orgIds":["org-eng"],"includeDescendants":false,"title":"异步广播","body":"应排队"}
                                """)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("ORG_BROADCAST"))
                .andExpect(jsonPath("$.fanoutStatus").value("PENDING"))
                .andExpect(jsonPath("$.fanoutJobId").isNotEmpty())
                .andExpect(jsonPath("$.entryIds", hasSize(0)));

        // org-eng already has OP + mem-eng-a; bulk adds n more → async path.
        Integer entries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_entry e JOIN inbox_message m ON m.message_id = e.message_id WHERE m.tenant_id = ? AND m.title = ?",
                Integer.class,
                TENANT,
                "异步广播");
        org.assertj.core.api.Assertions.assertThat(entries).isEqualTo(n + 2);
        Integer bulkEntries = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM inbox_entry e
                JOIN inbox_message m ON m.message_id = e.message_id
                WHERE m.tenant_id = ? AND m.title = ? AND e.recipient_subject_id LIKE 'bulk-%'
                """,
                Integer.class,
                TENANT,
                "异步广播");
        org.assertj.core.api.Assertions.assertThat(bulkEntries).isEqualTo(n);
        cleanupBulkMembers();
    }


    @Test
    void viewerWithoutBroadcastForbidden() throws Exception {
        mockMvc.perform(post(InboxEndpoint.BASE + "/broadcast")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orgIds":["org-eng"],"title":"x","body":"y"}
                                """)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    private void cleanupBulkMembers() {
        jdbc.update("DELETE FROM membership WHERE subject_id LIKE 'bulk-%'");
        jdbc.update("DELETE FROM subject WHERE subject_id LIKE 'bulk-%'");
    }

    private void cleanupInbox() {
        jdbc.update("DELETE FROM inbox_entry WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM inbox_attachment WHERE tenant_id = ?", TENANT);
        jdbc.update(
                "DELETE FROM inbox_fanout_recipient WHERE job_id IN (SELECT job_id FROM inbox_fanout_job WHERE tenant_id = ?)",
                TENANT);
        jdbc.update("DELETE FROM inbox_fanout_job WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM inbox_message WHERE tenant_id = ?", TENANT);
    }

    private void ensureTenant(String id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, id);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')", id, id);
        }
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
            return new LocalDirectoryObjectStorage(Files.createTempDirectory("inbox-a5-objects"));
        }

        @Bean
        ChunkedBlobUploadStore chunkedBlobUploadStore() throws Exception {
            return new LocalDirChunkedBlobUploadStore(
                    Files.createTempDirectory("inbox-a5-chunks"), Clock.systemUTC());
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
    }
}
