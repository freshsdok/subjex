package com.subjex.platform.app.security;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * OperatorMfaSecurityTest — Slice D：TOTP 登记、登录挑战、恢复码一次性、关闭需口令+码。
 */
@WebMvcTest(
        controllers = {
            OperatorAuthEndpoint.class,
            OperatorMfaEndpoint.class,
            OperatorSelfEndpoint.class,
            OperatorManagementEndpoint.class
        })
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OperatorMfaSecurityTest.GuardConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OperatorMfaSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AesGcmSecretCipher mfaSecretCipher;

    @Test
    void enrolledOperatorCannotGetTokensWithPasswordAlone() throws Exception {
        enrollViewer();

        MvcResult login = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + VIEWER + "\",\"password\":\"" + VIEWER_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.mfaToken").isNotEmpty())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();
        JsonNode challenge = objectMapper.readTree(login.getResponse().getContentAsString());
        String mfaToken = challenge.path("mfaToken").asText();

        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());

        String totp = currentTotpForSubject(subjectId(VIEWER));
        MvcResult verified = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"" + totp + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        String access = objectMapper.readTree(verified.getResponse().getContentAsString()).path("accessToken").asText();
        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnrolled").value(true));
    }

    @Test
    void recoveryCodeWorksOnce() throws Exception {
        List<String> recovery = enrollViewer();
        String code = recovery.get(0);

        JsonNode challenge = loginChallenge(VIEWER, VIEWER_PASSWORD);
        String mfaToken = challenge.path("mfaToken").asText();
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        JsonNode second = loginChallenge(VIEWER, VIEWER_PASSWORD);
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\""
                                + second.path("mfaToken").asText()
                                + "\",\"code\":\""
                                + code
                                + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disableRequiresPasswordAndCode() throws Exception {
        enrollViewer();
        String access = completeLogin(VIEWER, VIEWER_PASSWORD);

        mockMvc.perform(post(OperatorMfaEndpoint.PATH + "/totp/disable")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\",\"code\":\"" + currentTotpForSubject(subjectId(VIEWER)) + "\"}"))
                .andExpect(status().isUnauthorized());

        // Password OK but wrong code
        mockMvc.perform(post(OperatorMfaEndpoint.PATH + "/totp/disable")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + VIEWER_PASSWORD + "\",\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(OperatorMfaEndpoint.PATH + "/totp/disable")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\""
                                + VIEWER_PASSWORD
                                + "\",\"code\":\""
                                + currentTotpForSubject(subjectId(VIEWER))
                                + "\"}"))
                .andExpect(status().isNoContent());

        // After disable, password-only login issues tokens again.
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + VIEWER + "\",\"password\":\"" + VIEWER_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.mfaRequired").doesNotExist());
    }

    @Test
    void meReportsMfaFlags() throws Exception {
        mockMvc.perform(get(OperatorSelfEndpoint.PATH).with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnrolled").value(false))
                .andExpect(jsonPath("$.mfaEnrollmentRequired").value(false));
    }

    private List<String> enrollViewer() throws Exception {
        String access = loginTokens(VIEWER, VIEWER_PASSWORD).path("accessToken").asText();
        MvcResult start = mockMvc.perform(post(OperatorMfaEndpoint.PATH + "/totp/start")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(jsonPath("$.otpauthUri").isNotEmpty())
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
        // Secret at rest must be ciphertext, not raw Base32.
        String stored = jdbc.queryForObject(
                "SELECT secret_encrypted FROM operator_mfa_totp WHERE subject_id = ?",
                String.class,
                subjectId(VIEWER));
        assertThat(stored).startsWith("v1.");
        assertThat(stored).doesNotContain(secret);
        assertThat(mfaSecretCipher.decryptUtf8(stored)).isEqualTo(secret);
        return list;
    }

    private String completeLogin(String login, String password) throws Exception {
        JsonNode challenge = loginChallenge(login, password);
        String totp = currentTotpForSubject(subjectId(login));
        MvcResult verified = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\""
                                + challenge.path("mfaToken").asText()
                                + "\",\"code\":\""
                                + totp
                                + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(verified.getResponse().getContentAsString()).path("accessToken").asText();
    }

    private JsonNode loginChallenge(String login, String password) throws Exception {
        MvcResult result = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + login + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode loginTokens(String login, String password) throws Exception {
        MvcResult result = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + login + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
