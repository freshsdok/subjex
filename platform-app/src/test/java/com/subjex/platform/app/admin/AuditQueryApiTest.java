package com.subjex.platform.app.admin;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AuditQueryApiTest — OBS-1 filtered audit API (tenant/actor/action/time).
 * 目的：审计筛选 API（租户/操作者/动作/时间）。
 */
@WebMvcTest(controllers = {AuditApiEndpoint.class, AuditPage.class})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    AuditQueryApiTest.SliceConfiguration.class
})
class AuditQueryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditPort auditPort;

    private String consentEntryId;

    private String otherTenantEntryId;

    @BeforeEach
    void seedEntries() {
        Instant t1 = Instant.parse("2026-10-15T02:00:00Z");
        Instant t2 = Instant.parse("2026-10-15T04:00:00Z");
        Instant t3 = Instant.parse("2026-10-15T06:00:00Z");
        consentEntryId = "ae-consent-" + UUID.randomUUID();
        auditPort.record(new AuditEntry(
                "ae-login-" + UUID.randomUUID(),
                "platform",
                LocalOperatorSeeder.IDENTITY_ID,
                "operator.auth.login",
                "console",
                AuditOutcome.ALLOWED,
                t1));
        auditPort.record(new AuditEntry(
                consentEntryId,
                "platform",
                LocalOperatorSeeder.IDENTITY_ID,
                "oauth.consent.approve",
                "tp-client",
                AuditOutcome.ALLOWED,
                t2));
        otherTenantEntryId = "ae-other-tenant-" + UUID.randomUUID();
        auditPort.record(new AuditEntry(
                otherTenantEntryId,
                "tenant-other",
                LocalOperatorSeeder.IDENTITY_ID,
                "oauth.token.client_credentials",
                "sa-x",
                AuditOutcome.ALLOWED,
                t3));
        auditPort.record(new AuditEntry(
                "ae-noise-" + UUID.randomUUID(),
                "platform",
                LocalOperatorSeeder.IDENTITY_ID,
                "config.override",
                "noise",
                AuditOutcome.ALLOWED,
                Instant.parse("2026-10-14T01:00:00Z")));
    }

    @Test
    void filterByActionPrefixReturnsConsent() throws Exception {
        mockMvc.perform(get(JsonApi.BASE + "/audit")
                        .param("action", "oauth.consent")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.auditEntryId == '" + consentEntryId + "')]").isNotEmpty())
                .andExpect(jsonPath("$.appliedFilters.actionName").value("oauth.consent"));
    }

    @Test
    void filterByTenantAndActor() throws Exception {
        mockMvc.perform(get(JsonApi.BASE + "/audit")
                        .param("tenantId", "tenant-other")
                        .param("actor", OperatorDirectoryTestConfiguration.OPERATOR)
                        .param("action", "oauth.token.client_credentials")
                        .param("from", "2026-10-15T05:30:00Z")
                        .param("to", "2026-10-15T06:30:00Z")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.auditEntryId == '" + otherTenantEntryId + "')]").isNotEmpty())
                .andExpect(jsonPath("$.appliedFilters.tenantId").value("tenant-other"));
    }

    @Test
    void filterByTimeWindow() throws Exception {
        mockMvc.perform(get(JsonApi.BASE + "/audit")
                        .param("action", "oauth.consent.approve")
                        .param("from", "2026-10-15T03:00:00Z")
                        .param("to", "2026-10-15T05:00:00Z")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].auditEntryId").value(consentEntryId));
    }

    @Test
    void consoleAcceptsSameFilters() throws Exception {
        mockMvc.perform(get(AuditPage.PATH)
                        .param("action", "operator.auth")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk());
    }

    @TestConfiguration
    static class SliceConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        JdbcAdminReader jdbcAdminReader(JdbcTemplate jdbc) {
            return new JdbcAdminReader(jdbc);
        }
    }
}
