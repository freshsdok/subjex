package com.subjex.platform.app.idp;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.JdbcOperatorIdpLinkStore;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * MeIdpLinkEndpoint — self-service IdP link MVP (ID-4). Bind issuer+subject to current operator.
 * 自助绑定 IdP（MVP）：当前操作员绑定 issuer+subject。
 */
@RestController
public class MeIdpLinkEndpoint {

    public static final String PATH = JsonApi.BASE + "/me/idp-link";

    private final JdbcOperatorIdpLinkStore linkStore;
    private final OperatorActionAudit audit;

    public MeIdpLinkEndpoint(JdbcOperatorIdpLinkStore linkStore, OperatorActionAudit audit) {
        this.linkStore = Objects.requireNonNull(linkStore, "linkStore");
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

    @PutMapping(PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bind(@AuthenticationPrincipal OperatorPrincipal operator, @RequestBody BindRequest body) {
        requireHuman(operator);
        if (body == null || body.issuer() == null || body.idpSubject() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issuer and idpSubject required");
        }
        try {
            linkStore.bindByLoginName(operator.getUsername(), body.issuer(), body.idpSubject());
            audit.record(operator, "operator.idp.self-link", body.issuer(), AuditOutcome.ALLOWED);
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

    public record BindRequest(String issuer, String idpSubject) {}

    public record LinkDocument(String loginName, String issuer, String idpSubject, boolean linked) {}
}
