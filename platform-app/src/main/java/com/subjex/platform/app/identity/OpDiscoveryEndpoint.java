package com.subjex.platform.app.identity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpDiscoveryEndpoint — OpenID discovery + JWKS (ID-1).
 * OpenID 发现文档与 JWKS。
 */
@RestController
public class OpDiscoveryEndpoint {

    public static final String DISCOVERY_PATH = "/.well-known/openid-configuration";
    public static final String JWKS_PATH = "/oauth2/jwks";

    private final OpProperties properties;
    private final OpSigningKeyService keys;

    public OpDiscoveryEndpoint(OpProperties properties, OpSigningKeyService keys) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @GetMapping(value = DISCOVERY_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> discovery() {
        String issuer = properties.issuer();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("issuer", issuer);
        doc.put("authorization_endpoint", issuer + "/oauth2/authorize");
        doc.put("token_endpoint", issuer + "/oauth2/token");
        doc.put("jwks_uri", issuer + JWKS_PATH);
        doc.put("introspection_endpoint", issuer + OpIntrospectionEndpoint.PATH);
        doc.put("userinfo_endpoint", issuer + OpUserinfoEndpoint.PATH);
        doc.put("response_types_supported", List.of("code"));
        doc.put("grant_types_supported", List.of("authorization_code", "client_credentials"));
        doc.put("subject_types_supported", List.of("public"));
        doc.put("id_token_signing_alg_values_supported", List.of("RS256"));
        doc.put("token_endpoint_auth_methods_supported", List.of("none", "client_secret_post"));
        doc.put("introspection_endpoint_auth_methods_supported", List.of("client_secret_post"));
        doc.put("code_challenge_methods_supported", List.of("S256"));
        doc.put("scopes_supported", List.of("openid", "profile"));
        return doc;
    }

    @GetMapping(value = JWKS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public String jwks() {
        return keys.jwksJson();
    }
}
