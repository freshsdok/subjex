package com.subjex.platform.app.idp;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.InvalidOperatorTokenException;
import com.subjex.platform.app.security.JdbcOperatorIdpLinkStore;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * MeIdpLinkEndpoint — self-service IdP link with proof-of-possession (ID-6 polish).
 * Bind only after completing IdP callback ({@code POST .../prove}); raw issuer+subject rejected.
 * 自助绑定须完成 IdP 回调持有证明；拒绝裸写 issuer+subject。
 */
@RestController
public class MeIdpLinkEndpoint {

    public static final String PATH = JsonApi.BASE + "/me/idp-link";

    private final JdbcOperatorIdpLinkStore linkStore;
    private final IdpAdapterRegistry registry;
    private final IdpLinkProofStore proofs;
    private final OperatorActionAudit audit;

    public MeIdpLinkEndpoint(
            JdbcOperatorIdpLinkStore linkStore,
            IdpAdapterRegistry registry,
            IdpLinkProofStore proofs,
            OperatorActionAudit audit) {
        this.linkStore = Objects.requireNonNull(linkStore, "linkStore");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.proofs = Objects.requireNonNull(proofs, "proofs");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @GetMapping(PATH)
    public LinkDocument get(@AuthenticationPrincipal OperatorPrincipal operator) {
        requireHuman(operator);
        return linkStore
                .findByLoginName(operator.getUsername())
                .map(l -> new LinkDocument(operator.getUsername(), l.issuer(), l.idpSubject(), true))
                .orElseGet(() -> new LinkDocument(operator.getUsername(), null, null, false));
    }

    /**
     * Prove possession: exchange IdP callback for a short-lived proof ticket.
     * 持有证明：用 IdP 回调换短票。
     */
    @PostMapping(PATH + "/prove")
    public ProofDocument prove(
            @AuthenticationPrincipal OperatorPrincipal operator, @RequestBody ProveRequest body) {
        requireHuman(operator);
        if (body == null || body.providerId() == null || body.providerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "providerId required");
        }
        IdpAdapter adapter = registry
                .find(body.providerId().trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "idp-unknown"));
        if (!adapter.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "idp-disabled");
        }
        IdpCallbackRequest cb = new IdpCallbackRequest(body.code(), body.state(), body.jsCode());
        IdpExternalIdentity identity;
        try {
            identity = adapter.handleCallback(cb);
        } catch (InvalidOperatorTokenException ex) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, ex.getMessage() == null ? "idp-invalid" : ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage() == null ? "idp-unavailable" : ex.getMessage());
        }
        String proof = proofs.issue(
                operator.getUsername(), identity.issuer(), identity.subject(), adapter.id());
        audit.record(operator, "operator.idp.self-link.prove", identity.issuer(), AuditOutcome.ALLOWED);
        return new ProofDocument(proof, identity.issuer(), identity.subject(), adapter.id(), IdpLinkProofStore.TTL.toSeconds());
    }

    /**
     * Bind using a consumed proof ticket (PoP). Rejects body without proofToken.
     * 用短票绑定；无 proofToken 拒绝。
     */
    @PutMapping(PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bind(@AuthenticationPrincipal OperatorPrincipal operator, @RequestBody BindRequest body) {
        requireHuman(operator);
        if (body == null || body.proofToken() == null || body.proofToken().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "proofToken required (complete POST /me/idp-link/prove first)");
        }
        IdpLinkProofStore.Proof proof = proofs
                .consume(body.proofToken(), operator.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "idp-proof-invalid"));
        try {
            linkStore.bindByLoginName(operator.getUsername(), proof.issuer(), proof.idpSubject());
            audit.record(operator, "operator.idp.self-link", proof.issuer(), AuditOutcome.ALLOWED);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    @DeleteMapping(PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@AuthenticationPrincipal OperatorPrincipal operator) {
        requireHuman(operator);
        try {
            linkStore.unlinkByLoginName(operator.getUsername());
            audit.record(operator, "operator.idp.self-unlink", operator.getUsername(), AuditOutcome.ALLOWED);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    private static void requireHuman(OperatorPrincipal operator) {
        if (operator == null || operator.serviceAccount()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login required");
        }
    }

    public record ProveRequest(String providerId, String code, String state, String jsCode) {}

    public record ProofDocument(
            String proofToken, String issuer, String idpSubject, String providerId, long expiresInSeconds) {}

    /** {@code proofToken} required; issuer/idpSubject in body are ignored (taken from proof). */
    public record BindRequest(String proofToken, String issuer, String idpSubject) {}

    public record LinkDocument(String loginName, String issuer, String idpSubject, boolean linked) {}
}
