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

import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.tenant.TenantIsolationTags;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
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
 * InboxApiSecurityTest — IM-2: REST AuthZ + own-entry isolation + tenant fail-closed.
 * 门禁：权限 / 本人收件 / 租户失败关闭。
 */
@WebMvcTest(controllers = InboxEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    InboxApiSecurityTest.InboxTestConfiguration.class
})
@Tag(TenantIsolationTags.TENANT_ISOLATION)
class InboxApiSecurityTest {

    private static final String TENANT = "tenant-inbox-api";
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
    void seedTenantAndGrants() {
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
    void happyPathSendListUnreadMarkReadAndDetail() throws Exception {
        mockMvc.perform(post(InboxEndpoint.BASE + "/system")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "维护通知",
                                  "body": "今晚维护",
                                  "severity": "WARN",
                                  "recipientSubjectIds": ["%s", "%s"],
                                  "deepLink": "/inbox"
                                }
                                """.formatted(LocalOperatorSeeder.SUBJECT_ID, VIEWER_SUBJECT))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").isNotEmpty())
                .andExpect(jsonPath("$.entryIds", hasSize(2)));

        mockMvc.perform(get(InboxEndpoint.BASE + "/unread-count")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        MvcResult list = mockMvc.perform(get(InboxEndpoint.BASE + "/entries")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .param("unreadOnly", "true")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].title").value("维护通知"))
                .andReturn();

        JsonNode entries = objectMapper.readTree(list.getResponse().getContentAsString()).get("entries");
        String entryId = entries.get(0).get("entryId").asText();

        mockMvc.perform(get(InboxEndpoint.BASE + "/entries/" + entryId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("今晚维护"))
                .andExpect(jsonPath("$.severity").value("WARN"));

        mockMvc.perform(post(InboxEndpoint.BASE + "/entries/" + entryId + "/read")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(1));

        mockMvc.perform(get(InboxEndpoint.BASE + "/unread-count")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        // Idempotent re-read — 幂等再读
        mockMvc.perform(post(InboxEndpoint.BASE + "/entries/read")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryIds\":[\"" + entryId + "\"]}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(1));
    }

    @Test
    void viewerCannotSendSystemButCanReadOwn() throws Exception {
        mockMvc.perform(post(InboxEndpoint.BASE + "/system")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "拒绝",
                                  "body": "无发送权",
                                  "recipientSubjectIds": ["%s"]
                                }
                                """.formatted(VIEWER_SUBJECT))
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(InboxEndpoint.BASE + "/system")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "给读者",
                                  "body": "可读",
                                  "recipientSubjectIds": ["%s"]
                                }
                                """.formatted(VIEWER_SUBJECT))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(get(InboxEndpoint.BASE + "/entries")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].title").value("给读者"));
    }

    @Test
    void crossSubjectGetAndMarkAreForbidden() throws Exception {
        MvcResult send = mockMvc.perform(post(InboxEndpoint.BASE + "/system")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "仅读者",
                                  "body": "他人不可读",
                                  "recipientSubjectIds": ["%s"]
                                }
                                """.formatted(VIEWER_SUBJECT))
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();

        String entryId = objectMapper
                .readTree(send.getResponse().getContentAsString())
                .get("entryIds")
                .get(0)
                .asText();

        mockMvc.perform(get(InboxEndpoint.BASE + "/entries/" + entryId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(InboxEndpoint.BASE + "/entries/" + entryId + "/read")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(InboxEndpoint.BASE + "/entries/" + entryId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("仅读者"));
    }

    @Test
    void missingTenantOrWrongGrantIsForbidden() throws Exception {
        mockMvc.perform(get(InboxEndpoint.BASE + "/unread-count")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(InboxEndpoint.BASE + "/unread-count")
                        .header(TenantEnforcementFilter.TENANT_HEADER, "tenant-no-grant")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get(InboxEndpoint.BASE + "/unread-count")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class InboxTestConfiguration {

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
        InboxService inboxService(
                InboxPort inboxPort, OperatorTenantAccess tenantAccess, TenantGuard tenantGuard) {
            return new InboxService(inboxPort, tenantAccess, tenantGuard, Clock.systemUTC());
        }
    }
}
