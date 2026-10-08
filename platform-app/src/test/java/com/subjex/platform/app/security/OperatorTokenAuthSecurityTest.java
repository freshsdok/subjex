package com.subjex.platform.app.security;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
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
 * OperatorTokenAuthSecurityTest — Slice C：登录签发、Bearer 访问、刷新轮换、重放吊销、口令改后失效。
 */
@WebMvcTest(controllers = {OperatorAuthEndpoint.class, OperatorSelfEndpoint.class, OperatorManagementEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OperatorTokenAuthSecurityTest.GuardConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OperatorTokenAuthSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void wrongPasswordIsUnauthorized() throws Exception {
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + OPERATOR + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginIssuesBearerPairAndMeWorksWithAccessToken() throws Exception {
        JsonNode tokens = login(OPERATOR, OPERATOR_PASSWORD);
        assertThat(tokens.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(tokens.path("expiresIn").asLong()).isEqualTo(1800L);
        assertThat(tokens.path("accessToken").asText()).isNotBlank();
        assertThat(tokens.path("refreshToken").asText()).isNotBlank();

        String access = tokens.path("accessToken").asText();
        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginName").value(OPERATOR));

        // Raw tokens must not appear in the hash columns.
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM operator_access_token WHERE token_hash = ?",
                        Integer.class,
                        access))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operator_access_token", Integer.class)).isEqualTo(1);
    }

    @Test
    void refreshRotatesAndOldRefreshIsRejectedWithFamilyRevokeOnReuse() throws Exception {
        JsonNode first = login(OPERATOR, OPERATOR_PASSWORD);
        String oldRefresh = first.path("refreshToken").asText();
        String oldAccess = first.path("accessToken").asText();

        MvcResult refreshResult = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", not(equalTo(oldAccess))))
                .andExpect(jsonPath("$.refreshToken", not(equalTo(oldRefresh))))
                .andReturn();
        JsonNode second = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        String newAccess = second.path("accessToken").asText();
        String newRefresh = second.path("refreshToken").asText();

        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());

        // Old access should be revoked with rotation.
        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + oldAccess))
                .andExpect(status().isUnauthorized());

        // Reuse of old refresh → family revoke → new refresh also dead.
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshFamily() throws Exception {
        JsonNode tokens = login(OPERATOR, OPERATOR_PASSWORD);
        String access = tokens.path("accessToken").asText();
        String refresh = tokens.path("refreshToken").asText();

        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledAccountRejectsRefresh() throws Exception {
        JsonNode tokens = login(VIEWER, VIEWER_PASSWORD);
        String refresh = tokens.path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/operators/" + VIEWER + "/disable").with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordChangeRevokesTokens() throws Exception {
        JsonNode tokens = login(VIEWER, VIEWER_PASSWORD);
        String access = tokens.path("accessToken").asText();
        String refresh = tokens.path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/operators/" + VIEWER + "/password")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"brand-new-pass\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void basicAuthStillWorksForScripts() throws Exception {
        mockMvc.perform(get(OperatorSelfEndpoint.PATH).with(httpBasic(OPERATOR, OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginName").value(OPERATOR));
    }

    private JsonNode login(String login, String password) throws Exception {
        MvcResult result = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + login + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
