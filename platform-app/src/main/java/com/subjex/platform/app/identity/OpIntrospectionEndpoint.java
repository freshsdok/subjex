package com.subjex.platform.app.identity;

import com.nimbusds.jwt.JWTClaimsSet;
import com.subjex.platform.app.security.JdbcOperatorTokenStore;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpIntrospectionEndpoint — RFC 7662 token introspection (ID-6).
 * Supports OP JWT access tokens and legacy opaque operator access tokens.
 * 令牌自省：OP JWT 与遗留不透明访问令牌。
 */
@RestController
public class OpIntrospectionEndpoint {

    public static final String PATH = "/oauth2/introspect";

    private final JdbcOauthClientStore clients;
    private final OpJwtService jwt;
    private final JdbcOperatorTokenStore tokenStore;
    private final OpProperties properties;

    public OpIntrospectionEndpoint(
            JdbcOauthClientStore clients,
            OpJwtService jwt,
            JdbcOperatorTokenStore tokenStore,
            OpProperties properties) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.jwt = Objects.requireNonNull(jwt, "jwt");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @PostMapping(value = PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> introspect(@RequestParam MultiValueMap<String, String> form) {
        if (!authenticateClient(form)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_client", "error_description", "client auth required"));
        }
        String token = form == null ? null : form.getFirst("token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.ok(inactive());
        }
        Optional<JWTClaimsSet> jwtClaims = jwt.verifyAccessToken(token);
        if (jwtClaims.isPresent()) {
            return ResponseEntity.ok(fromJwt(jwtClaims.get()));
        }
        Optional<OperatorPrincipal> opaque = tokenStore.resolveAccessToken(token);
        if (opaque.isPresent()) {
            return ResponseEntity.ok(fromOpaque(opaque.get()));
        }
        return ResponseEntity.ok(inactive());
    }

    private boolean authenticateClient(MultiValueMap<String, String> form) {
        if (form == null) {
            return false;
        }
        String clientId = form.getFirst("client_id");
        String secret = form.getFirst("client_secret");
        if (clientId == null || clientId.isBlank()) {
            return false;
        }
        OauthClientRecord client = clients.findByClientId(clientId.trim()).filter(OauthClientRecord::active).orElse(null);
        if (client == null) {
            return false;
        }
        // Public clients cannot introspect (need confidential client secret).
        // 公开客户端不可自省（须机密客户端密钥）。
        if (client.publicClient()) {
            return false;
        }
        return clients.verifySecret(client, secret);
    }

    private Map<String, Object> fromJwt(JWTClaimsSet claims) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", true);
        body.put("iss", claims.getIssuer());
        body.put("sub", claims.getSubject());
        body.put("token_type", "Bearer");
        if (claims.getExpirationTime() != null) {
            body.put("exp", claims.getExpirationTime().toInstant().getEpochSecond());
        }
        if (claims.getIssueTime() != null) {
            body.put("iat", claims.getIssueTime().toInstant().getEpochSecond());
        }
        Object clientId = claims.getClaim(OpJwtService.CLAIM_CLIENT_ID);
        if (clientId != null) {
            body.put("client_id", String.valueOf(clientId));
        }
        Object scope = claims.getClaim(OpJwtService.CLAIM_SCOPE);
        if (scope != null && !String.valueOf(scope).isBlank()) {
            body.put("scope", String.valueOf(scope));
        }
        Object login = claims.getClaim(OpJwtService.CLAIM_LOGIN);
        if (login != null) {
            body.put("username", String.valueOf(login));
        }
        Object tenant = claims.getClaim(OpJwtService.CLAIM_TENANT_ID);
        if (tenant != null) {
            body.put("tenant_id", String.valueOf(tenant));
        }
        body.put("token_use", "access");
        return body;
    }

    private Map<String, Object> fromOpaque(OperatorPrincipal operator) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", true);
        body.put("iss", properties.issuer());
        body.put("sub", operator.subjectId());
        body.put("username", operator.getUsername());
        body.put("token_type", "Bearer");
        body.put("token_use", "access");
        body.put("token_format", "opaque");
        return body;
    }

    private static Map<String, Object> inactive() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", false);
        return body;
    }
}
