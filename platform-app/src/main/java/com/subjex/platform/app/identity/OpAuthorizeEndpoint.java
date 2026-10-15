package com.subjex.platform.app.identity;

import com.subjex.platform.app.security.OperatorPrincipal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * OpAuthorizeEndpoint — Authorization Code + PKCE; third-party consent challenge (ID-1/ID-3).
 * <p>
 * First-party clients issue a code immediately. Third-party clients without covering consent
 * return a JSON consent challenge for the console to render.
 * 一等客户端直接发码；第三方未同意时返回 JSON 同意挑战供控制台渲染。
 */
@RestController
public class OpAuthorizeEndpoint {

    public static final String PATH = "/oauth2/authorize";

    private final JdbcOauthClientStore clients;
    private final JdbcOauthAuthCodeStore codes;
    private final JdbcOauthConsentStore consents;
    private final JdbcOauthConsentChallengeStore challenges;
    private final JdbcOauthScopeCatalog scopes;
    private final OpProperties properties;
    private final JdbcTemplate jdbc;

    public OpAuthorizeEndpoint(
            JdbcOauthClientStore clients,
            JdbcOauthAuthCodeStore codes,
            JdbcOauthConsentStore consents,
            JdbcOauthConsentChallengeStore challenges,
            JdbcOauthScopeCatalog scopes,
            OpProperties properties,
            JdbcTemplate jdbc) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.codes = Objects.requireNonNull(codes, "codes");
        this.consents = Objects.requireNonNull(consents, "consents");
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @GetMapping(PATH)
    public ResponseEntity<?> authorize(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam("response_type") String responseType,
            @RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam("scope") String scope,
            @RequestParam("state") String state,
            @RequestParam("code_challenge") String codeChallenge,
            @RequestParam(value = "code_challenge_method", defaultValue = "S256") String codeChallengeMethod,
            @RequestParam(value = "nonce", required = false) String nonce) {
        if (operator == null || operator.serviceAccount()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login required");
        }
        if (!"code".equals(responseType)) {
            return oauthErrorJson(state, "unsupported_response_type", "only code");
        }
        OauthClientRecord client = clients
                .findByClientId(clientId)
                .filter(OauthClientRecord::active)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown client"));
        if (!client.allowsGrant("authorization_code")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "grant not allowed");
        }
        if (!client.allowsRedirect(redirectUri)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri mismatch");
        }
        if (client.requirePkce()) {
            if (codeChallenge == null || codeChallenge.isBlank()) {
                return oauthErrorJson(state, "invalid_request", "code_challenge required");
            }
            if (!"S256".equalsIgnoreCase(codeChallengeMethod)) {
                return oauthErrorJson(state, "invalid_request", "S256 required");
            }
        }
        Set<String> requested = filterScopes(client, scope);
        if (requested.isEmpty() || !requested.contains("openid")) {
            return oauthErrorJson(state, "invalid_scope", "openid required");
        }
        boolean firstParty =
                client.firstParty() || client.clientId().equals(properties.consoleClientId());
        if (!firstParty && client.requiresConsent()) {
            if (!consents.covers(operator.subjectId(), client.clientId(), requested)) {
                return consentChallenge(
                        operator, client, redirectUri.trim(), requested, codeChallenge, "S256", state, nonce);
            }
            // Restrict issued scopes to previously consented ∩ requested.
            // 签发范围限制为已同意 ∩ 本次请求。
            Set<String> consented = consents
                    .findActive(operator.subjectId(), client.clientId())
                    .map(JdbcOauthConsentStore.ConsentGrant::scopeSet)
                    .orElse(Set.of());
            requested = requested.stream()
                    .filter(consented::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return issueCodeRedirect(
                operator, client, redirectUri.trim(), join(requested), codeChallenge, "S256", state, nonce);
    }

    ResponseEntity<?> issueCodeRedirect(
            OperatorPrincipal operator,
            OauthClientRecord client,
            String redirectUri,
            String grantedScopes,
            String codeChallenge,
            String codeChallengeMethod,
            String state,
            String nonce) {
        String accountId = lookupAccountId(operator.getUsername());
        Instant exp = Instant.now().plus(properties.authCodeTtl());
        JdbcOauthAuthCodeStore.IssuedCode issued = codes.issue(
                client.clientId(),
                operator.subjectId(),
                accountId,
                operator.getUsername(),
                redirectUri,
                grantedScopes,
                codeChallenge,
                codeChallengeMethod,
                nonce,
                exp);
        String location = redirectUri
                + (redirectUri.contains("?") ? "&" : "?")
                + "code="
                + url(issued.rawCode())
                + "&state="
                + url(state == null ? "" : state);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private ResponseEntity<Map<String, Object>> consentChallenge(
            OperatorPrincipal operator,
            OauthClientRecord client,
            String redirectUri,
            Set<String> requested,
            String codeChallenge,
            String codeChallengeMethod,
            String state,
            String nonce) {
        String accountId = lookupAccountId(operator.getUsername());
        Instant exp = Instant.now().plus(properties.authCodeTtl());
        var challenge = challenges.create(
                operator.subjectId(),
                accountId,
                operator.getUsername(),
                client.clientId(),
                redirectUri,
                join(requested),
                codeChallenge,
                codeChallengeMethod,
                state,
                nonce,
                exp);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consent_required", true);
        body.put("challenge_id", challenge.challengeId());
        body.put("client_id", client.clientId());
        body.put("client_name", client.clientName());
        body.put("state", state);
        body.put(
                "scopes",
                requested.stream()
                        .map(name -> {
                            Map<String, String> row = new LinkedHashMap<>();
                            row.put("name", name);
                            scopes.find(name).ifPresentOrElse(
                                    s -> {
                                        row.put("description_en", s.descriptionEn());
                                        row.put("description_zh", s.descriptionZh());
                                    },
                                    () -> {
                                        row.put("description_en", name);
                                        row.put("description_zh", name);
                                    });
                            return row;
                        })
                        .toList());
        // Console can open /oauth/consent?challenge_id=...
        // 控制台可打开 /oauth/consent?challenge_id=...
        body.put("consent_path", "/oauth/consent?challenge_id=" + url(challenge.challengeId()));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private Set<String> filterScopes(OauthClientRecord client, String requested) {
        Set<String> allowed = new LinkedHashSet<>(client.scopes());
        Set<String> catalog = scopes.knownNames();
        allowed.retainAll(catalog);
        if (requested == null || requested.isBlank()) {
            return allowed;
        }
        return Arrays.stream(requested.trim().split("\\s+"))
                .map(String::trim)
                .filter(allowed::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String lookupAccountId(String loginName) {
        return jdbc.query(
                        "SELECT account_id FROM account WHERE login_name = ?",
                        (row, n) -> row.getString("account_id"),
                        loginName)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "account missing"));
    }

    private static ResponseEntity<Map<String, String>> oauthErrorJson(String state, String error, String desc) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", desc);
        if (state != null) {
            body.put("state", state);
        }
        return ResponseEntity.badRequest().body(body);
    }

    static String join(Set<String> scopes) {
        return scopes.stream().sorted().collect(Collectors.joining(" "));
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
