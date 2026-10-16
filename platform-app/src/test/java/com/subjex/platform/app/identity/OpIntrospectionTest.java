package com.subjex.platform.app.identity;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.OperatorAuthEndpoint;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
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
 * OpIntrospectionTest — ID-6 introspection + userinfo for JWT and opaque tokens.
 * 目的：自省与 UserInfo（JWT / 不透明令牌）。
 */
@WebMvcTest(
        controllers = {
            OpDiscoveryEndpoint.class,
            OpTokenEndpoint.class,
            OpIntrospectionEndpoint.class,
            OpUserinfoEndpoint.class,
            OperatorAuthEndpoint.class,
            OperatorSelfEndpoint.class
        })
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OpIntrospectionTest.GuardConfiguration.class
})
class OpIntrospectionTest {

    private static final String TENANT = "tenant-intro";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcOauthClientStore clients;

    @Test
    void discoveryListsIntrospectAndUserinfo() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.introspection_endpoint").value("http://127.0.0.1:8080/oauth2/introspect"))
                .andExpect(jsonPath("$.userinfo_endpoint").value("http://127.0.0.1:8080/oauth2/userinfo"));
    }

    @Test
    void introspectJwtAndUserinfo() throws Exception {
        String secret = clients.createServiceAccount("sa-intro", "sa-intro", TENANT, List.of("page.read"))
                .clientSecret();
        MvcResult token = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "sa-intro")
                        .param("client_secret", secret)
                        .param("scope", "page.read"))
                .andExpect(status().isOk())
                .andReturn();
        String jwt = objectMapper.readTree(token.getResponse().getContentAsString()).path("access_token").asText();

        mockMvc.perform(post("/oauth2/introspect")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", jwt)
                        .param("client_id", "sa-intro")
                        .param("client_secret", secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.client_id").value("sa-intro"))
                .andExpect(jsonPath("$.token_use").value("access"));

        mockMvc.perform(get("/oauth2/userinfo").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("sa-intro"))
                .andExpect(jsonPath("$.preferred_username").value("sa:sa-intro"));
    }

    @Test
    void introspectOpaqueOperatorToken() throws Exception {
        String secret = clients.createServiceAccount("sa-intro-op", "sa-intro-op", TENANT, List.of("page.read"))
                .clientSecret();
        MvcResult login = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + OPERATOR + "\",\"password\":\"" + OPERATOR_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        String opaque = body.path("accessToken").asText();

        mockMvc.perform(post("/oauth2/introspect")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", opaque)
                        .param("client_id", "sa-intro-op")
                        .param("client_secret", secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.username").value(OPERATOR))
                .andExpect(jsonPath("$.token_format").value("opaque"));

        mockMvc.perform(post("/oauth2/introspect")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "dead-token")
                        .param("client_id", "sa-intro-op")
                        .param("client_secret", secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void introspectWithoutClientAuthIsUnauthorized() throws Exception {
        mockMvc.perform(post("/oauth2/introspect")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "anything"))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
