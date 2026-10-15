package com.subjex.platform.app.identity;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * OauthConsentEndpoint — consent challenge decision + list/revoke grants (ID-3).
 * 同意挑战决策与同意列表/吊销。
 */
@RestController
public class OauthConsentEndpoint {

    public static final String PATH = JsonApi.BASE + "/oauth/consent";

    private final JdbcOauthConsentStore consents;
    private final JdbcOauthConsentChallengeStore challenges;
    private final JdbcOauthClientStore clients;
    private final JdbcOauthScopeCatalog scopes;
    private final JdbcOauthAuthCodeStore codes;
    private final OpProperties properties;
    private final OperatorActionAudit audit;

    public OauthConsentEndpoint(
            JdbcOauthConsentStore consents,
            JdbcOauthConsentChallengeStore challenges,
            JdbcOauthClientStore clients,
            JdbcOauthScopeCatalog scopes,
            JdbcOauthAuthCodeStore codes,
            OpProperties properties,
            OperatorActionAudit audit) {
        this.consents = Objects.requireNonNull(consents, "consents");
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.codes = Objects.requireNonNull(codes, "codes");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @GetMapping(PATH + "/scopes")
    public List<ScopeDocument> catalog() {
        return scopes.listAll().stream()
                .map(s -> new ScopeDocument(s.scopeName(), s.descriptionEn(), s.descriptionZh(), s.permissionName()))
                .toList();
    }

    @GetMapping(PATH + "/challenges/{challengeId}")
    public ChallengeDocument challenge(
            @AuthenticationPrincipal OperatorPrincipal operator, @PathVariable String challengeId) {
        requireHuman(operator);
        var ch = challenges
                .findLive(challengeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "challenge missing"));
        if (!ch.subjectId().equals(operator.subjectId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your challenge");
        }
        OauthClientRecord client = clients
                .findByClientId(ch.clientId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "client missing"));
        return toChallengeDocument(ch, client);
    }

    @PostMapping(PATH + "/decision")
    public ResponseEntity<?> decide(
            @AuthenticationPrincipal OperatorPrincipal operator, @RequestBody ConsentDecisionRequest body) {
        requireHuman(operator);
        if (body == null || body.challengeId() == null || body.decision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "challengeId and decision required");
        }
        var ch = challenges
                .findLive(body.challengeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "challenge missing"));
        if (!ch.subjectId().equals(operator.subjectId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your challenge");
        }
        OauthClientRecord client = clients
                .findByClientId(ch.clientId())
                .filter(OauthClientRecord::active)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "client missing"));
        String decision = body.decision().trim().toLowerCase();
        if ("deny".equals(decision)) {
            challenges.consume(ch.challengeId());
            audit.record(operator, "oauth.consent.deny", client.clientId(), AuditOutcome.REFUSED);
            String location = ch.redirectUri()
                    + (ch.redirectUri().contains("?") ? "&" : "?")
                    + "error=access_denied"
                    + "&state="
                    + url(ch.state() == null ? "" : ch.state());
            return ResponseEntity.ok(Map.of("redirect_to", location, "decision", "deny"));
        }
        if (!"approve".equals(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be approve or deny");
        }
        Set<String> requested = JdbcOauthConsentStore.split(ch.scopesRequested());
        Set<String> approved = requested;
        if (body.scopes() != null && !body.scopes().isEmpty()) {
            approved = body.scopes().stream()
                    .filter(requested::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (!approved.contains("openid")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "openid required");
        }
        consents.grant(operator.subjectId(), client.clientId(), approved);
        challenges.consume(ch.challengeId());
        audit.record(operator, "oauth.consent.approve", client.clientId(), AuditOutcome.ALLOWED);
        InstantExp issued = issueCode(operator, client, ch, OpAuthorizeEndpoint.join(approved));
        String location = ch.redirectUri()
                + (ch.redirectUri().contains("?") ? "&" : "?")
                + "code="
                + url(issued.rawCode())
                + "&state="
                + url(ch.state() == null ? "" : ch.state());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("redirect_to", location);
        out.put("decision", "approve");
        out.put("scope", OpAuthorizeEndpoint.join(approved));
        return ResponseEntity.ok(out);
    }

    @GetMapping(PATH + "/grants")
    public List<ConsentGrantDocument> listGrants(@AuthenticationPrincipal OperatorPrincipal operator) {
        requireHuman(operator);
        return consents.listForSubject(operator.subjectId()).stream()
                .map(g -> new ConsentGrantDocument(
                        g.clientId(),
                        g.scopes(),
                        g.grantedAt().toString(),
                        g.revokedAt() == null ? null : g.revokedAt().toString(),
                        g.active()))
                .toList();
    }

    @DeleteMapping(PATH + "/grants/{clientId}")
    public ResponseEntity<?> revoke(
            @AuthenticationPrincipal OperatorPrincipal operator, @PathVariable String clientId) {
        requireHuman(operator);
        boolean ok = consents.revoke(operator.subjectId(), clientId);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no active consent");
        }
        audit.record(operator, "oauth.consent.revoke", clientId, AuditOutcome.ALLOWED);
        return ResponseEntity.noContent().build();
    }

    private InstantExp issueCode(
            OperatorPrincipal operator,
            OauthClientRecord client,
            JdbcOauthConsentChallengeStore.Challenge ch,
            String grantedScopes) {
        var issued = codes.issue(
                client.clientId(),
                operator.subjectId(),
                ch.accountId(),
                operator.getUsername(),
                ch.redirectUri(),
                grantedScopes,
                ch.codeChallenge(),
                ch.codeChallengeMethod(),
                ch.nonce(),
                java.time.Instant.now().plus(properties.authCodeTtl()));
        return new InstantExp(issued.rawCode());
    }

    private ChallengeDocument toChallengeDocument(
            JdbcOauthConsentChallengeStore.Challenge ch, OauthClientRecord client) {
        List<ScopeDocument> scopeDocs = Arrays.stream(ch.scopesRequested().split("\\s+"))
                .filter(s -> !s.isBlank())
                .map(name -> scopes.find(name)
                        .map(s -> new ScopeDocument(
                                s.scopeName(), s.descriptionEn(), s.descriptionZh(), s.permissionName()))
                        .orElse(new ScopeDocument(name, name, name, null)))
                .toList();
        return new ChallengeDocument(
                ch.challengeId(),
                client.clientId(),
                client.clientName(),
                ch.state(),
                scopeDocs,
                ch.expiresAt().toString());
    }

    private static void requireHuman(OperatorPrincipal operator) {
        if (operator == null || operator.serviceAccount()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login required");
        }
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record InstantExp(String rawCode) {}

    public record ConsentDecisionRequest(String challengeId, String decision, List<String> scopes) {}

    public record ScopeDocument(String name, String descriptionEn, String descriptionZh, String permissionName) {}

    public record ChallengeDocument(
            String challengeId,
            String clientId,
            String clientName,
            String state,
            List<ScopeDocument> scopes,
            String expiresAt) {}

    public record ConsentGrantDocument(
            String clientId, String scopes, String grantedAt, String revokedAt, boolean active) {}
}
