package com.subjex.platform.app.capability;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CapabilityApiSecurityTest — GET/POST capabilities：401 无凭据、403 无 page.read、有权限 200；未知 id / 缺 inputText 为 400。
 */
@WebMvcTest(controllers = CapabilityApiEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    CapabilityApiSecurityTest.SliceConfiguration.class
})
class CapabilityApiSecurityTest {

    private static final String BARE = "platform-bare-cap";
    private static final String BARE_PASSWORD = "bare-pass";
    private static final String RUN_PATH = CapabilityApiEndpoint.PATH + "/algo.hashFingerprint/run";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void bareOperatorWithoutPageRead() {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account WHERE login_name = ?", Integer.class, BARE);
        if (exists != null && exists == 0) {
            jdbc.update(
                    "INSERT INTO account (account_id, login_name, account_state) VALUES ('account-bare-cap', ?, 'ACTIVE')",
                    BARE);
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES ('subject-bare-cap', 'BareCap', 'PERSON')");
            jdbc.update(
                    """
                    INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                    VALUES ('identity-bare-cap', 'account-bare-cap', 'subject-bare-cap', 'platform', 'ACTIVE')
                    """);
            jdbc.update(
                    "INSERT INTO operator_credential (account_id, password_hash) VALUES ('account-bare-cap', ?)",
                    passwordEncoder.encode(BARE_PASSWORD));
        }
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(CapabilityApiEndpoint.PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(RUN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputText\":\"hello\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bareOperatorWithoutPageReadGets403() throws Exception {
        mockMvc.perform(get(CapabilityApiEndpoint.PATH).with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(RUN_PATH)
                        .with(httpBasic(BARE, BARE_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputText\":\"hello\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerWithPageReadCanListCapabilities() throws Exception {
        mockMvc.perform(get(CapabilityApiEndpoint.PATH).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.length()").value(4))
                .andExpect(jsonPath("$.capabilities[0].id").value("algo.hashFingerprint"))
                .andExpect(jsonPath("$.capabilities[0].kind").value("ALGORITHM"))
                .andExpect(jsonPath("$.capabilities[1].id").value("algo.normalizeWhitespace"))
                .andExpect(jsonPath("$.capabilities[1].kind").value("ALGORITHM"))
                .andExpect(jsonPath("$.capabilities[2].id").value("ai.suggestTitlePreview"))
                .andExpect(jsonPath("$.capabilities[2].kind").value("AI"))
                .andExpect(jsonPath("$.capabilities[3].id").value("ai.summarizePreview"))
                .andExpect(jsonPath("$.capabilities[3].kind").value("AI"));
    }

    @Test
    void viewerCanRunKnownCapability() throws Exception {
        mockMvc.perform(post(RUN_PATH)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputText\":\"hello-subjex\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilityId").value("algo.hashFingerprint"))
                .andExpect(jsonPath("$.result").isString())
                .andExpect(jsonPath("$.result").isNotEmpty());
    }

    @Test
    void viewerCanRunAiPreviewStub() throws Exception {
        mockMvc.perform(post(CapabilityApiEndpoint.PATH + "/ai.summarizePreview/run")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputText\":\"short note\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilityId").value("ai.summarizePreview"))
                .andExpect(jsonPath("$.result").value(
                        org.hamcrest.Matchers.startsWith("[ai.summarizePreview] model-gateway stub preview:")));
    }

    @Test
    void unknownCapabilityRunIs400() throws Exception {
        mockMvc.perform(post(CapabilityApiEndpoint.PATH + "/algo.nope/run")
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputText\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("unknown")));
    }

    @Test
    void missingInputTextIs400() throws Exception {
        mockMvc.perform(post(RUN_PATH)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("inputText")));
        mockMvc.perform(post(RUN_PATH)
                        .with(httpBasic(VIEWER, VIEWER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputText\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("inputText")));
    }

    @TestConfiguration
    static class SliceConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }

        @Bean
        CapabilityCatalog capabilityCatalog() {
            return new CapabilityCatalog();
        }

        @Bean
        CapabilityRunner capabilityRunner(CapabilityCatalog capabilityCatalog) {
            return new CapabilityRunner(capabilityCatalog);
        }
    }
}
