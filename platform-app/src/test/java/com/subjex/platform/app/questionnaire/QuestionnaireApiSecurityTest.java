package com.subjex.platform.app.questionnaire;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.ratelimit.SingleProcessRateLimit;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.tenant.TenantIsolationTags;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.questionnaire.QuestionnairePort;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * QuestionnaireApiSecurityTest — Q6a: REST AuthZ for questionnaire.read|write + public collect.
 * 门禁：读写权限分权、租户失败关闭、匿名采集放行。
 */
@WebMvcTest(controllers = QuestionnaireEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    QuestionnaireApiSecurityTest.QuestionnaireTestConfiguration.class
})
@Tag(TenantIsolationTags.TENANT_ISOLATION)
class QuestionnaireApiSecurityTest {

    private static final String TENANT = "tenant-q-api";
    private static final Instant T0 = Instant.parse("2026-10-22T06:00:00Z");

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
        // Isolate questionnaire rows across tests in the shared H2 — 共享 H2 上隔离问卷行。
        jdbc.update("DELETE FROM questionnaire_answer");
        jdbc.update("DELETE FROM questionnaire_response");
        jdbc.update("DELETE FROM questionnaire_collect_token");
        jdbc.update("DELETE FROM questionnaire_item");
        jdbc.update("DELETE FROM questionnaire");
        tenantAccess.ensureGrant(LocalOperatorSeeder.SUBJECT_ID, OperatorTenantAccess.ALL_TENANTS);
        tenantAccess.ensureGrant("subject-viewer", TENANT);
    }

    @Test
    void anonymousOperatorPathsUnauthorized() throws Exception {
        mockMvc.perform(get(QuestionnaireEndpoint.BASE).header(TenantEnforcementFilter.TENANT_HEADER, TENANT))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(QuestionnaireEndpoint.BASE)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"collectMode\":\"AUTH_END_USER\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCanReadButNotWrite() throws Exception {
        MvcResult created = mockMvc.perform(post(QuestionnaireEndpoint.BASE)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"课程反馈\",\"collectMode\":\"AUTH_END_USER\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("课程反馈"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String qid = objectMapper.readTree(created.getResponse().getContentAsString()).get("questionnaireId").asText();

        mockMvc.perform(get(QuestionnaireEndpoint.BASE)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionnaires[?(@.questionnaireId == '%s')]", qid).exists());

        mockMvc.perform(get(QuestionnaireEndpoint.BASE + "/" + qid)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionnaireId").value(qid));

        mockMvc.perform(post(QuestionnaireEndpoint.BASE)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"拒绝\",\"collectMode\":\"AUTH_END_USER\"}")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(put(QuestionnaireEndpoint.BASE + "/" + qid + "/items")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":"i1","sortOrder":0,"questionType":"TEXT_SHORT","prompt":"意见","required":true,"optionsJson":"[]"}]}
                                """)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(QuestionnaireEndpoint.BASE + "/" + qid + "/publish")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorHappyPathPublishStatsCsvAndPublicCollect() throws Exception {
        MvcResult created = mockMvc.perform(post(QuestionnaireEndpoint.BASE)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"匿名调研\",\"collectMode\":\"ANON_LINK\"}")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String qid = objectMapper.readTree(created.getResponse().getContentAsString()).get("questionnaireId").asText();

        mockMvc.perform(put(QuestionnaireEndpoint.BASE + "/" + qid + "/items")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[
                                  {"itemId":"q1","sortOrder":0,"questionType":"SINGLE_CHOICE","prompt":"满意度","required":true,"optionsJson":"[\\"好\\",\\"一般\\"]"},
                                  {"itemId":"q2","sortOrder":1,"questionType":"TEXT_SHORT","prompt":"补充","required":false,"optionsJson":"[]"}
                                ]}
                                """)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));

        mockMvc.perform(post(QuestionnaireEndpoint.BASE + "/" + qid + "/publish")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        MvcResult tokenResult = mockMvc.perform(get(QuestionnaireEndpoint.BASE + "/" + qid + "/collect-token")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenId").isNotEmpty())
                .andReturn();
        String tokenId =
                objectMapper.readTree(tokenResult.getResponse().getContentAsString()).get("tokenId").asText();

        mockMvc.perform(get(QuestionnaireEndpoint.BASE + "/public/" + tokenId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("匿名调研"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mockMvc.perform(post(QuestionnaireEndpoint.BASE + "/public/" + tokenId + "/responses")
                        .header("X-Client-Key", "client-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"answers":[
                                  {"itemId":"q1","valueJson":"\\"好\\""},
                                  {"itemId":"q2","valueJson":"\\"不错\\""}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countsForStats").value(true))
                .andExpect(jsonPath("$.respondentRealm").value("ANON"));

        mockMvc.perform(get(QuestionnaireEndpoint.BASE + "/" + qid + "/stats")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCount").value(1));

        mockMvc.perform(get(QuestionnaireEndpoint.BASE + "/" + qid + "/export.csv")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("response_id")));

        mockMvc.perform(get(QuestionnaireEndpoint.BASE + "/" + qid + "/responses")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responses", hasSize(1)));

        mockMvc.perform(post(QuestionnaireEndpoint.BASE + "/" + qid + "/close")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT)
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void missingTenantRejected() throws Exception {
        mockMvc.perform(get(QuestionnaireEndpoint.BASE).with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPublicTokenIs404() throws Exception {
        mockMvc.perform(get(QuestionnaireEndpoint.BASE + "/public/no-such-token"))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration
    static class QuestionnaireTestConfiguration {

        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(T0, ZoneOffset.UTC);
        }

        @Bean
        TransactionTemplate transactionTemplate(DataSource operatorTables) {
            return new TransactionTemplate(new DataSourceTransactionManager(operatorTables));
        }

        @Bean
        RateLimitPort rateLimitPort() {
            return new SingleProcessRateLimit(100, Duration.ofMinutes(1), Clock.fixed(T0, ZoneOffset.UTC));
        }

        @Bean
        QuestionnairePort questionnairePort(
                JdbcTemplate jdbc, TransactionTemplate transactionTemplate, Clock clock, RateLimitPort rateLimitPort) {
            return new JdbcQuestionnairePort(jdbc, transactionTemplate, clock, rateLimitPort);
        }

        @Bean
        QuestionnaireService questionnaireService(
                QuestionnairePort questionnairePort, OperatorTenantAccess tenantAccess, TenantGuard tenantGuard) {
            return new QuestionnaireService(questionnairePort, tenantAccess, tenantGuard);
        }
    }
}
