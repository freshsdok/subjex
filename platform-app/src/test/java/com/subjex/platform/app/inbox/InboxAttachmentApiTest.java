package com.subjex.platform.app.inbox;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * InboxAttachmentApiTest — IM-A2: staging upload, system send bind, own-entry download fail-closed.
 * 暂存上传、系统发送绑定、本人下载失败关闭。
 */
@WebMvcTest(controllers = InboxEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    InboxAttachmentApiTest.Config.class
})
@Tag(TenantIsolationTags.TENANT_ISOLATION)
class InboxAttachmentApiTest {

    private static final String TENANT = "tenant-inbox-a2";
    private static final String VIEWER_SUBJECT = "subject-viewer";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OperatorTenantAccess tenantAccess;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
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
    void directUploadBindSendAndOwnDownload() throws Exception {
        byte[] bytes = "hello-inbox".getBytes(StandardCharsets.UTF_8);
        MvcResult staged = mockMvc.perform(post(InboxEndpoint.BASE + "/attachments")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .param("fileName", "hello.txt")
                        .param("contentType", "text/plain")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bytes)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stagingId").isNotEmpty())
                .andExpect(jsonPath("$.fileName").value("hello.txt"))
                .andReturn();
        String stagingId =
                objectMapper.readTree(staged.getResponse().getContentAsString()).get("stagingId").asText();

        MvcResult send = mockMvc.perform(post(InboxEndpoint.BASE + "/system")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "带附件",
                                  "body": "请查收",
                                  "recipientSubjectIds": ["%s", "%s"],
                                  "attachmentStagingIds": ["%s"]
                                }
                                """.formatted(LocalOperatorSeeder.SUBJECT_ID, VIEWER_SUBJECT, stagingId))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentIds", hasSize(1)))
                .andReturn();

        JsonNode sendJson = objectMapper.readTree(send.getResponse().getContentAsString());
        String operatorEntryId = null;
        String viewerEntryId = null;
        // Resolve entry ids via list for each user
        MvcResult opList = mockMvc.perform(get(InboxEndpoint.BASE + "/entries")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .param("unreadOnly", "true")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        operatorEntryId = objectMapper
                .readTree(opList.getResponse().getContentAsString())
                .get("entries")
                .get(0)
                .get("entryId")
                .asText();
        String attachmentId = sendJson.get("attachmentIds").get(0).asText();

        mockMvc.perform(get(InboxEndpoint.BASE + "/entries/" + operatorEntryId + "/attachments")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments", hasSize(1)))
                .andExpect(jsonPath("$.attachments[0].fileName").value("hello.txt"));

        mockMvc.perform(get(InboxEndpoint.BASE
                                + "/entries/"
                                + operatorEntryId
                                + "/attachments/"
                                + attachmentId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain"))
                .andExpect(content().bytes(bytes));

        // Viewer own entry can download; operator cannot use viewer's entry id path as non-owner —
        // 读者本人可下；操作员不能用他人 entry 下载
        MvcResult viewerList = mockMvc.perform(get(InboxEndpoint.BASE + "/entries")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        viewerEntryId = objectMapper
                .readTree(viewerList.getResponse().getContentAsString())
                .get("entries")
                .get(0)
                .get("entryId")
                .asText();

        mockMvc.perform(get(InboxEndpoint.BASE
                                + "/entries/"
                                + viewerEntryId
                                + "/attachments/"
                                + attachmentId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(InboxEndpoint.BASE
                                + "/entries/"
                                + viewerEntryId
                                + "/attachments/"
                                + attachmentId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    @Test
    void chunkedUploadThenSend() throws Exception {
        byte[] bytes = "chunked-payload-ok".getBytes(StandardCharsets.UTF_8);
        MvcResult init = mockMvc.perform(post(InboxEndpoint.BASE + "/attachments/uploads")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fileName": "note.txt",
                                  "contentType": "text/plain",
                                  "totalBytes": %d
                                }
                                """.formatted(bytes.length))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").isNotEmpty())
                .andReturn();
        String uploadId =
                objectMapper.readTree(init.getResponse().getContentAsString()).get("uploadId").asText();

        mockMvc.perform(put(InboxEndpoint.BASE + "/attachments/uploads/" + uploadId + "/chunks/0")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(bytes)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(1));

        MvcResult complete = mockMvc.perform(post(InboxEndpoint.BASE
                                + "/attachments/uploads/"
                                + uploadId
                                + "/complete")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"note.txt\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stagingId").isNotEmpty())
                .andReturn();
        String stagingId =
                objectMapper.readTree(complete.getResponse().getContentAsString()).get("stagingId").asText();

        mockMvc.perform(post(InboxEndpoint.BASE + "/system")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "分片附件",
                                  "body": "ok",
                                  "recipientSubjectIds": ["%s"],
                                  "attachmentStagingIds": ["%s"]
                                }
                                """.formatted(LocalOperatorSeeder.SUBJECT_ID, stagingId))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentIds", hasSize(1)));
    }

    @Test
    void viewerCannotUploadOrBindForeignStaging() throws Exception {
        mockMvc.perform(post(InboxEndpoint.BASE + "/attachments")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .param("fileName", "x.txt")
                        .param("contentType", "text/plain")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("x".getBytes(StandardCharsets.UTF_8))
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());

        MvcResult staged = mockMvc.perform(post(InboxEndpoint.BASE + "/attachments")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .param("fileName", "y.txt")
                        .param("contentType", "text/plain")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("y".getBytes(StandardCharsets.UTF_8))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        // Staging exists but viewer lacks send.system — cannot send at all
        mockMvc.perform(post(InboxEndpoint.BASE + "/system")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "x",
                                  "body": "y",
                                  "recipientSubjectIds": ["%s"],
                                  "attachmentStagingIds": ["%s"]
                                }
                                """.formatted(
                                VIEWER_SUBJECT,
                                objectMapper
                                        .readTree(staged.getResponse().getContentAsString())
                                        .get("stagingId")
                                        .asText()))
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsDisallowedContentType() throws Exception {
        mockMvc.perform(post(InboxEndpoint.BASE + "/attachments")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .param("fileName", "x.exe")
                        .param("contentType", "application/octet-stream")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[] {1, 2, 3})
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());
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
            return new LocalDirectoryObjectStorage(Files.createTempDirectory("inbox-a2-objects"));
        }

        @Bean
        ChunkedBlobUploadStore chunkedBlobUploadStore() throws Exception {
            return new LocalDirChunkedBlobUploadStore(
                    Files.createTempDirectory("inbox-a2-chunks"), Clock.systemUTC());
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
