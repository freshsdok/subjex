package com.subjex.platform.app.identity;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.SignedJWT;
import com.subjex.platform.app.security.OperatorAuthEndpoint;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * OpConsentTest — ID-3 consent required → approve → token scopes; revoke forces re-consent.
 * 目的：需同意、批准后发令、吊销后再次需同意。
 */
@WebMvcTest(
        controllers = {
            OpAuthorizeEndpoint.class,
            OpTokenEndpoint.class,
            OpDiscoveryEndpoint.class,
            OauthConsentEndpoint.class,
            OauthClientAdminEndpoint.class,
            OperatorAuthEndpoint.class
        })
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OpConsentTest.GuardConfiguration.class
})
class OpConsentTest {

    private static final String REDIRECT = "http://127.0.0.1:9999/callback";
    private String clientId;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcOauthClientStore clients;

    private String opaque;
    private String verifier;
    private String challenge;

    @BeforeEach
    void setUp() throws Exception {
        opaque = loginAccess();
        clientId = "tp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        clients.createAuthorizationCodeClient(
                clientId,
                "Third Party App",
                List.of(REDIRECT),
                List.of("openid", "profile", "page.read", "config.read"),
                true,
                false);
        verifier = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("v-" + UUID.randomUUID() + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(md.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void consentRequiredThenApproveIssuesTokenWithConsentedScopes() throws Exception {
        MvcResult auth = mockMvc.perform(get("/oauth2/authorize")
                        .param("response_type", "code")
                        .param("client_id", clientId)
                        .param("redirect_uri", REDIRECT)
                        .param("scope", "openid profile page.read")
                        .param("state", "st-1")
                        .param("code_challenge", challenge)
                        .param("code_challenge_method", "S256")
                        .header("Authorization", "Bearer " + opaque))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.consent_required").value(true))
                .andExpect(jsonPath("$.challenge_id").isString())
                .andExpect(jsonPath("$.client_id").value(clientId))
                .andReturn();
        String challengeId = objectMapper
                .readTree(auth.getResponse().getContentAsString())
                .path("challenge_id")
                .asText();

        MvcResult decision = mockMvc.perform(post(OauthConsentEndpoint.PATH + "/decision")
                        .header("Authorization", "Bearer " + opaque)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"challengeId":"%s","decision":"approve","scopes":["openid","profile","page.read"]}
                                """
                                        .formatted(challengeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("approve"))
                .andExpect(jsonPath("$.redirect_to").isString())
                .andReturn();
        String redirectTo = objectMapper
                .readTree(decision.getResponse().getContentAsString())
                .path("redirect_to")
                .asText();
        assertThat(redirectTo).startsWith(REDIRECT);
        String code = queryParam(redirectTo, "code");
        assertThat(code).isNotBlank();

        MvcResult token = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", clientId)
                        .param("code", code)
                        .param("redirect_uri", REDIRECT)
                        .param("code_verifier", verifier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isString())
                .andReturn();
        String access = objectMapper
                .readTree(token.getResponse().getContentAsString())
                .path("access_token")
                .asText();
        String scopeClaim = SignedJWT.parse(access).getJWTClaimsSet().getStringClaim("scope");
        assertThat(scopeClaim).contains("openid");
        assertThat(scopeClaim).contains("page.read");
        assertThat(scopeClaim).doesNotContain("config.read");

        // Second authorize with same scopes skips consent (already granted).
        // 已同意后再次授权不再挑战。
        mockMvc.perform(get("/oauth2/authorize")
                        .param("response_type", "code")
                        .param("client_id", clientId)
                        .param("redirect_uri", REDIRECT)
                        .param("scope", "openid page.read")
                        .param("state", "st-2")
                        .param("code_challenge", challenge)
                        .param("code_challenge_method", "S256")
                        .header("Authorization", "Bearer " + opaque))
                .andExpect(status().isFound());
    }

    @Test
    void revokeForcesConsentAgain() throws Exception {
        // Approve once
        MvcResult auth = mockMvc.perform(get("/oauth2/authorize")
                        .param("response_type", "code")
                        .param("client_id", clientId)
                        .param("redirect_uri", REDIRECT)
                        .param("scope", "openid profile")
                        .param("state", "st")
                        .param("code_challenge", challenge)
                        .param("code_challenge_method", "S256")
                        .header("Authorization", "Bearer " + opaque))
                .andExpect(status().isConflict())
                .andReturn();
        String challengeId = objectMapper
                .readTree(auth.getResponse().getContentAsString())
                .path("challenge_id")
                .asText();
        mockMvc.perform(post(OauthConsentEndpoint.PATH + "/decision")
                        .header("Authorization", "Bearer " + opaque)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"" + challengeId + "\",\"decision\":\"approve\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(OauthConsentEndpoint.PATH + "/grants").header("Authorization", "Bearer " + opaque))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientId").value(clientId))
                .andExpect(jsonPath("$[0].active").value(true));

        mockMvc.perform(delete(OauthConsentEndpoint.PATH + "/grants/" + clientId)
                        .header("Authorization", "Bearer " + opaque))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/oauth2/authorize")
                        .param("response_type", "code")
                        .param("client_id", clientId)
                        .param("redirect_uri", REDIRECT)
                        .param("scope", "openid profile")
                        .param("state", "st")
                        .param("code_challenge", challenge)
                        .param("code_challenge_method", "S256")
                        .header("Authorization", "Bearer " + opaque))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.consent_required").value(true));
    }

    @Test
    void scopeCatalogListsOpenidAndApiScopes() throws Exception {
        mockMvc.perform(get(OauthConsentEndpoint.PATH + "/scopes").header("Authorization", "Bearer " + opaque))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='openid')]").exists())
                .andExpect(jsonPath("$[?(@.name=='page.read')]").exists());
    }

    private String loginAccess() throws Exception {
        MvcResult result = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + OPERATOR + "\",\"password\":\"" + OPERATOR_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
    }

    private static String queryParam(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String part : url.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && name.equals(java.net.URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8))) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
