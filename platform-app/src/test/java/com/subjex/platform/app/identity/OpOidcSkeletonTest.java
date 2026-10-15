package com.subjex.platform.app.identity;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import com.subjex.platform.app.security.OperatorAuthEndpoint;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.OperatorSelfEndpoint;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
 * OpOidcSkeletonTest — ID-1 discovery, JWKS, PKCE authorize→token, dual Bearer JWT.
 * 目的：发现文档、JWKS、PKCE 换 JWT、JWT 与不透明 Bearer 双受理。
 */
@WebMvcTest(
        controllers = {
            OpDiscoveryEndpoint.class,
            OpAuthorizeEndpoint.class,
            OpTokenEndpoint.class,
            OperatorAuthEndpoint.class,
            OperatorSelfEndpoint.class
        })
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OpOidcSkeletonTest.GuardConfiguration.class
})
class OpOidcSkeletonTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OpSigningKeyService keys;

    @Test
    void discoveryAndJwks() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("http://127.0.0.1:8080"))
                .andExpect(jsonPath("$.authorization_endpoint").value("http://127.0.0.1:8080/oauth2/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value("http://127.0.0.1:8080/oauth2/token"))
                .andExpect(jsonPath("$.jwks_uri").value("http://127.0.0.1:8080/oauth2/jwks"))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"));

        MvcResult jwks = mockMvc.perform(get("/oauth2/jwks")).andExpect(status().isOk()).andReturn();
        JWKSet set = JWKSet.parse(jwks.getResponse().getContentAsString());
        assertThat(set.getKeyByKeyId(keys.kid())).isNotNull();
    }

    @Test
    void pkceAuthorizeTokenAndJwtDualAccept() throws Exception {
        JsonNode opaque = login(OPERATOR, OPERATOR_PASSWORD);
        String opaqueAccess = opaque.path("accessToken").asText();
        assertThat(opaqueAccess).isNotBlank();

        String verifier = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("v-" + UUID.randomUUID() + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(md.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = "st-" + UUID.randomUUID();
        String redirect = "http://127.0.0.1:3000/api/auth/callback";

        MvcResult auth = mockMvc.perform(get("/oauth2/authorize")
                        .param("response_type", "code")
                        .param("client_id", "subjex-console")
                        .param("redirect_uri", redirect)
                        .param("scope", "openid profile")
                        .param("state", state)
                        .param("code_challenge", challenge)
                        .param("code_challenge_method", "S256")
                        .header("Authorization", "Bearer " + opaqueAccess))
                .andExpect(status().isFound())
                .andReturn();
        String location = auth.getResponse().getHeader("Location");
        assertThat(location).startsWith(redirect);
        Map<String, String> q = query(location);
        assertThat(q.get("state")).isEqualTo(state);
        String code = q.get("code");
        assertThat(code).isNotBlank();

        MvcResult token = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", "subjex-console")
                        .param("code", code)
                        .param("redirect_uri", redirect)
                        .param("code_verifier", verifier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.access_token").isString())
                .andExpect(jsonPath("$.id_token").isString())
                .andReturn();
        JsonNode body = objectMapper.readTree(token.getResponse().getContentAsString());
        String accessJwt = body.path("access_token").asText();
        SignedJWT jwt = SignedJWT.parse(accessJwt);
        assertThat(jwt.verify(new RSASSAVerifier(keys.publicKey()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://127.0.0.1:8080");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("preferred_username")).isEqualTo(OPERATOR);

        // Dual-accept: JWT Bearer reaches /api/v1/me the same as opaque.
        // 双受理：JWT Bearer 与不透明令牌一样可访问 /api/v1/me。
        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + accessJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginName").value(OPERATOR));

        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + opaqueAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginName").value(OPERATOR));
    }

    @Test
    void authorizeWithoutLoginIsUnauthorized() throws Exception {
        mockMvc.perform(get("/oauth2/authorize")
                        .param("response_type", "code")
                        .param("client_id", "subjex-console")
                        .param("redirect_uri", "http://127.0.0.1:3000/api/auth/callback")
                        .param("scope", "openid")
                        .param("state", "s")
                        .param("code_challenge", "x")
                        .param("code_challenge_method", "S256"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode login(String login, String password) throws Exception {
        MvcResult result = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + login + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static Map<String, String> query(String location) {
        URI uri = URI.create(location);
        String raw = uri.getRawQuery();
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (raw == null) {
            return map;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(
                        java.net.URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
