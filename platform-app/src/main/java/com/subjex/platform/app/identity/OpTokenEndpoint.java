package com.subjex.platform.app.identity;

import com.subjex.platform.app.security.JdbcOperatorDirectory;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpTokenEndpoint — authorization_code (+ PKCE) and client_credentials (ID-1/ID-2).
 * 授权码（+ PKCE）与客户端凭证换 JWT。
 */
@RestController
public class OpTokenEndpoint {

    public static final String PATH = "/oauth2/token";

    private final JdbcOauthClientStore clients;
    private final JdbcOauthAuthCodeStore codes;
    private final OpJwtService jwt;
    private final JdbcOperatorDirectory directory;
    private final OpProperties properties;
    private final OperatorActionAudit audit;

    public OpTokenEndpoint(
            JdbcOauthClientStore clients,
            JdbcOauthAuthCodeStore codes,
            OpJwtService jwt,
            JdbcOperatorDirectory directory,
            OpProperties properties,
            OperatorActionAudit audit) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.codes = Objects.requireNonNull(codes, "codes");
        this.jwt = Objects.requireNonNull(jwt, "jwt");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @PostMapping(value = PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> token(@RequestParam MultiValueMap<String, String> form) {
        String grantType = form.getFirst("grant_type");
        if ("client_credentials".equals(grantType)) {
            return clientCredentials(form);
        }
        if ("authorization_code".equals(grantType)) {
            return authorizationCode(form);
        }
        return error("unsupported_grant_type", "authorization_code or client_credentials");
    }

    private ResponseEntity<?> clientCredentials(MultiValueMap<String, String> form) {
        String clientId = form.getFirst("client_id");
        String secret = form.getFirst("client_secret");
        OauthClientRecord client = clients
                .findByClientId(clientId)
                .filter(OauthClientRecord::active)
                .orElse(null);
        if (client == null || !client.serviceAccount()) {
            return error("invalid_client", "unknown or non-service client");
        }
        if (!clients.verifySecret(client, secret)) {
            return error("invalid_client", "client authentication failed");
        }
        String requested = form.getFirst("scope");
        String granted = filterScopes(client, requested);
        if (granted.isBlank()) {
            return error("invalid_scope", "no permitted scopes");
        }
        // Requested scopes that are not allowed → invalid_scope (strict).
        // 请求了未允许的 scope → invalid_scope（严格）。
        if (requested != null && !requested.isBlank()) {
            List<String> req = Arrays.stream(requested.trim().split("\\s+"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
            var allowed = client.scopes().stream().collect(Collectors.toSet());
            for (String s : req) {
                if (!allowed.contains(s)) {
                    return error("invalid_scope", "scope not allowed: " + s);
                }
            }
        }
        String access = jwt.mintClientCredentialsToken(client, granted);
        OperatorPrincipal sa = OperatorPrincipal.serviceAccount(
                client.clientId(), client.tenantId(), ScopePermissionBridge.toPermissions(granted));
        audit.record(sa, "oauth.token.client_credentials", client.clientId(), AuditOutcome.ALLOWED);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", access);
        body.put("token_type", "Bearer");
        body.put("expires_in", properties.accessTokenTtl().toSeconds());
        body.put("scope", granted);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<?> authorizationCode(MultiValueMap<String, String> form) {
        String clientId = form.getFirst("client_id");
        String code = form.getFirst("code");
        String redirectUri = form.getFirst("redirect_uri");
        String verifier = form.getFirst("code_verifier");
        OauthClientRecord client = clients
                .findByClientId(clientId)
                .filter(OauthClientRecord::active)
                .orElse(null);
        if (client == null) {
            return error("invalid_client", "unknown client");
        }
        if (!client.publicClient()) {
            String secret = form.getFirst("client_secret");
            if (!clients.verifySecret(client, secret)) {
                return error("invalid_client", "client auth required");
            }
        }
        var stored = codes.findLive(code).orElse(null);
        if (stored == null) {
            return error("invalid_grant", "code invalid or expired");
        }
        if (!client.clientId().equals(stored.clientId())) {
            return error("invalid_grant", "client mismatch");
        }
        if (redirectUri == null || !redirectUri.equals(stored.redirectUri())) {
            return error("invalid_grant", "redirect_uri mismatch");
        }
        if (client.requirePkce()
                && !JdbcOauthAuthCodeStore.verifyPkce(
                        stored.codeChallengeMethod(), stored.codeChallenge(), verifier)) {
            return error("invalid_grant", "pkce verification failed");
        }
        codes.consume(code);
        OperatorPrincipal operator;
        try {
            operator = (OperatorPrincipal) directory.loadUserByUsername(stored.loginName());
        } catch (UsernameNotFoundException | ClassCastException ex) {
            return error("invalid_grant", "subject unavailable");
        }
        if (!operator.isEnabled()) {
            return error("invalid_grant", "subject disabled");
        }
        String access = jwt.mintAccessToken(operator, client.clientId(), stored.scopes());
        String idToken = jwt.mintIdToken(operator, client.clientId(), stored.nonce());
        audit.record(operator, "oauth.token.authorization_code", client.clientId(), AuditOutcome.ALLOWED);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", access);
        body.put("token_type", "Bearer");
        body.put("expires_in", properties.accessTokenTtl().toSeconds());
        body.put("id_token", idToken);
        body.put("scope", stored.scopes());
        return ResponseEntity.ok(body);
    }

    private static String filterScopes(OauthClientRecord client, String requested) {
        var allowed = client.scopes().stream().collect(Collectors.toSet());
        if (requested == null || requested.isBlank()) {
            return allowed.stream().sorted().collect(Collectors.joining(" "));
        }
        return Arrays.stream(requested.trim().split("\\s+"))
                .filter(allowed::contains)
                .distinct()
                .collect(Collectors.joining(" "));
    }

    private static ResponseEntity<Map<String, String>> error(String code, String desc) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("error_description", desc);
        HttpStatus status = "invalid_client".equals(code) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(body);
    }
}
