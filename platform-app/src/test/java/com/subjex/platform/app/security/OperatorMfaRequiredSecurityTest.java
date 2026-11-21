package com.subjex.platform.app.security;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * OperatorMfaRequiredSecurityTest — purpose/gate WP1-2: under {@code prod}, unenrolled password
 * login is fail-closed; enrolled TOTP still verifies to a Bearer pair.
 * <p>
 * 目的/门禁 WP1-2：生产配置下未登记操作员口令登录失败关闭；已登记 TOTP 仍可校验签发。
 * Enrollment uses a pre-issued Bearer (same as an existing session or OIDC), not password login.
 * 登记走预先签发的 Bearer（已有会话或 OIDC），不走口令登录。
 */
@WebMvcTest(controllers = {OperatorAuthEndpoint.class, OperatorMfaEndpoint.class, OperatorSelfEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OperatorMfaRequiredSecurityTest.GuardConfiguration.class
})
@ActiveProfiles("prod")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OperatorMfaRequiredSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcOperatorDirectory directory;

    @Autowired
    private JdbcOperatorTokenStore tokenStore;

    @Autowired
    private AesGcmSecretCipher mfaSecretCipher;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void unenrolledPasswordLoginIsForbiddenAndDoesNotIssueTokens() throws Exception {
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + VIEWER + "\",\"password\":\"" + VIEWER_PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("mfa-enrollment-required"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void enrolledTotpStillVerifiesUnderProd() throws Exception {
        enrollViewerWithExistingSession();

        MvcResult login = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + VIEWER + "\",\"password\":\"" + VIEWER_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.mfaToken").isNotEmpty())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();
        String mfaToken = objectMapper.readTree(login.getResponse().getContentAsString()).path("mfaToken").asText();

        String totp = currentTotpForSubject(subjectId(VIEWER));
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"" + totp + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    /**
     * Enroll via a Bearer issued outside password login — 用口令登录以外签发的 Bearer 完成登记。
     */
    private void enrollViewerWithExistingSession() throws Exception {
        OperatorPrincipal operator = (OperatorPrincipal) directory.loadUserByUsername(VIEWER);
        String access = tokenStore.issue(operator, "wp1-2-test", "127.0.0.1").accessToken();
        MvcResult start = mockMvc.perform(post(OperatorMfaEndpoint.PATH + "/totp/start")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andReturn();
        String secret = objectMapper.readTree(start.getResponse().getContentAsString()).path("secret").asText();
        String code = TotpGenerator.currentCode(secret, Instant.now());
        MvcResult confirm = mockMvc.perform(post(OperatorMfaEndpoint.PATH + "/totp/confirm")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes").isArray())
                .andReturn();
        JsonNode codes = objectMapper.readTree(confirm.getResponse().getContentAsString()).path("recoveryCodes");
        List<String> list = new ArrayList<>();
        codes.forEach(n -> list.add(n.asText()));
        assertThat(list).hasSize(10);
    }

    private String subjectId(String login) {
        return jdbc.queryForObject(
                """
                SELECT si.subject_id FROM account a
                JOIN subject_identity si ON si.account_id = a.account_id
                WHERE a.login_name = ?
                """,
                String.class,
                login);
    }

    private String currentTotpForSubject(String subjectId) {
        String encrypted = jdbc.queryForObject(
                "SELECT secret_encrypted FROM operator_mfa_totp WHERE subject_id = ? AND confirmed_at IS NOT NULL",
                String.class,
                subjectId);
        return TotpGenerator.currentCode(mfaSecretCipher.decryptUtf8(encrypted), Instant.now());
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
