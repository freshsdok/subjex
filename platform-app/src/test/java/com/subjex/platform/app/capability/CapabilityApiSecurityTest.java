package com.subjex.platform.app.capability;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CapabilityApiSecurityTest — GET /capabilities：401 无凭据、403 无 page.read、有权限 200。
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
    }

    @Test
    void bareOperatorWithoutPageReadGets403() throws Exception {
        mockMvc.perform(get(CapabilityApiEndpoint.PATH).with(httpBasic(BARE, BARE_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerWithPageReadCanListCapabilities() throws Exception {
        mockMvc.perform(get(CapabilityApiEndpoint.PATH).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.length()").value(2))
                .andExpect(jsonPath("$.capabilities[0].id").value("algo.hashFingerprint"))
                .andExpect(jsonPath("$.capabilities[0].kind").value("ALGORITHM"))
                .andExpect(jsonPath("$.capabilities[1].id").value("ai.summarizePreview"))
                .andExpect(jsonPath("$.capabilities[1].kind").value("AI"));
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
    }
}
