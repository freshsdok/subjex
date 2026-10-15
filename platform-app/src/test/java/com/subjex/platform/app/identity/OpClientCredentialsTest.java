package com.subjex.platform.app.identity;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.SignedJWT;
import com.subjex.platform.app.security.OperatorAuthEndpoint;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.OperatorManagementEndpoint;
import com.subjex.platform.app.security.OperatorSelfEndpoint;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpClientCredentialsTest — ID-2 client_credentials happy path, wrong secret, scope deny, SA API.
 * 目的：客户端凭证发令、错密钥、scope 拒绝、服务账号调 API。
 */
@WebMvcTest(
        controllers = {
            OpTokenEndpoint.class,
            OpDiscoveryEndpoint.class,
            OauthClientAdminEndpoint.class,
            OperatorAuthEndpoint.class,
            OperatorSelfEndpoint.class,
            OperatorManagementEndpoint.class
        })
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OpClientCredentialsTest.GuardConfiguration.class
})
class OpClientCredentialsTest {

    private static final String TENANT = "tenant-sa";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcOauthClientStore clients;

    @Test
    void clientCredentialsHappyPathAndApiCall() throws Exception {
        String secret = registerSa("sa-happy", List.of("page.read", "admin.read"));
        MvcResult token = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "sa-happy")
                        .param("client_secret", secret)
                        .param("scope", "page.read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.access_token").isString())
                .andReturn();
        JsonNode body = objectMapper.readTree(token.getResponse().getContentAsString());
        String jwt = body.path("access_token").asText();
        SignedJWT parsed = SignedJWT.parse(jwt);
        assertThat(parsed.getJWTClaimsSet().getStringClaim("client_id")).isEqualTo("sa-happy");
        assertThat(parsed.getJWTClaimsSet().getStringClaim("tenant_id")).isEqualTo(TENANT);
        assertThat(parsed.getJWTClaimsSet().getClaim("preferred_username")).isNull();

        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginName").value("sa:sa-happy"))
                .andExpect(jsonPath("$.permissions[0]").value("page.read"));
    }

    @Test
    void wrongSecretIsUnauthorized() throws Exception {
        registerSa("sa-bad-secret", List.of("page.read"));
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "sa-bad-secret")
                        .param("client_secret", "totally-wrong-secret")
                        .param("scope", "page.read"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void scopeNotAllowedIsBadRequest() throws Exception {
        String secret = registerSa("sa-scope", List.of("page.read"));
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "sa-scope")
                        .param("client_secret", secret)
                        .param("scope", "operator.manage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    @Test
    void saTokenDeniedOnOperatorManage() throws Exception {
        String secret = registerSa("sa-limited", List.of("page.read"));
        MvcResult token = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "sa-limited")
                        .param("client_secret", secret))
                .andExpect(status().isOk())
                .andReturn();
        String jwt = objectMapper
                .readTree(token.getResponse().getContentAsString())
                .path("access_token")
                .asText();
        mockMvc.perform(get(OperatorManagementEndpoint.PATH).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCreateAndRotateSecret() throws Exception {
        String opaque = loginAccess();
        MvcResult created = mockMvc.perform(post(OauthClientAdminEndpoint.PATH)
                        .header("Authorization", "Bearer " + opaque)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"clientId":"sa-admin","clientName":"Admin SA","tenantId":"%s","scopes":["config.read"]}
                                """
                                        .formatted(TENANT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").value("sa-admin"))
                .andExpect(jsonPath("$.clientSecret").isString())
                .andReturn();
        String firstSecret = objectMapper
                .readTree(created.getResponse().getContentAsString())
                .path("clientSecret")
                .asText();

        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "sa-admin")
                        .param("client_secret", firstSecret))
                .andExpect(status().isOk());

        MvcResult rotated = mockMvc.perform(post(OauthClientAdminEndpoint.PATH + "/sa-admin/rotate-secret")
                        .header("Authorization", "Bearer " + opaque))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").isString())
                .andReturn();
        String second = objectMapper
                .readTree(rotated.getResponse().getContentAsString())
                .path("clientSecret")
                .asText();
        assertThat(second).isNotEqualTo(firstSecret);

        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "sa-admin")
                        .param("client_secret", firstSecret))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "sa-admin")
                        .param("client_secret", second))
                .andExpect(status().isOk());
    }

    private String registerSa(String clientId, List<String> scopes) {
        return clients.createServiceAccount(clientId, clientId, TENANT, scopes).clientSecret();
    }

    private String loginAccess() throws Exception {
        MvcResult result = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + OPERATOR + "\",\"password\":\"" + OPERATOR_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
