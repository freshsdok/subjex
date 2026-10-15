package com.subjex.platform.app.identity;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * OauthClientAdminEndpoint — register SA (ID-2) or third-party Auth Code clients (ID-3).
 * 登记服务账号或第三方授权码客户端。
 */
@RestController
public class OauthClientAdminEndpoint {

    public static final String PATH = JsonApi.BASE + "/oauth/clients";

    private final JdbcOauthClientStore clients;
    private final OperatorActionAudit audit;

    public OauthClientAdminEndpoint(JdbcOauthClientStore clients, OperatorActionAudit audit) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @GetMapping(PATH)
    public List<OauthClientDocument> list(@RequestParam("tenantId") String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId required");
        }
        return clients.listByTenant(tenantId).stream().map(OauthClientDocument::from).toList();
    }

    @PostMapping(PATH)
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedClientDocument create(
            @AuthenticationPrincipal OperatorPrincipal operator, @RequestBody CreateClientRequest body) {
        if (body == null || body.clientId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientId required");
        }
        String kind = body.kind() == null || body.kind().isBlank() ? "client_credentials" : body.kind().trim();
        try {
            if ("authorization_code".equals(kind)) {
                JdbcOauthClientStore.IssuedSecret issued = clients.createAuthorizationCodeClient(
                        body.clientId(),
                        body.clientName(),
                        body.redirectUris(),
                        body.scopes(),
                        body.publicClient() == null || body.publicClient(),
                        Boolean.TRUE.equals(body.firstParty()));
                audit.record(operator, "oauth.client.create", issued.clientId(), AuditOutcome.ALLOWED);
                return new IssuedClientDocument(
                        issued.clientId(), null, body.scopes(), issued.clientSecret(), kind);
            }
            if (body.tenantId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenantId required for client_credentials");
            }
            JdbcOauthClientStore.IssuedSecret issued = clients.createServiceAccount(
                    body.clientId(), body.clientName(), body.tenantId(), body.scopes());
            audit.record(operator, "oauth.client.create", issued.clientId(), AuditOutcome.ALLOWED);
            return new IssuedClientDocument(
                    issued.clientId(), body.tenantId(), body.scopes(), issued.clientSecret(), kind);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PostMapping(PATH + "/{clientId}/rotate-secret")
    public RotateSecretDocument rotate(
            @AuthenticationPrincipal OperatorPrincipal operator, @PathVariable("clientId") String clientId) {
        try {
            JdbcOauthClientStore.IssuedSecret issued = clients.rotateSecret(clientId);
            audit.record(operator, "oauth.client.rotate-secret", issued.clientId(), AuditOutcome.ALLOWED);
            return new RotateSecretDocument(issued.clientId(), issued.clientSecret());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    public record CreateClientRequest(
            String clientId,
            String clientName,
            String tenantId,
            List<String> scopes,
            String kind,
            List<String> redirectUris,
            Boolean publicClient,
            Boolean firstParty) {}

    public record IssuedClientDocument(
            String clientId, String tenantId, List<String> scopes, String clientSecret, String kind) {}

    public record RotateSecretDocument(String clientId, String clientSecret) {}

    public record OauthClientDocument(
            String clientId,
            String clientName,
            String tenantId,
            List<String> scopes,
            boolean active,
            boolean firstParty,
            boolean publicClient) {
        static OauthClientDocument from(OauthClientRecord row) {
            return new OauthClientDocument(
                    row.clientId(),
                    row.clientName(),
                    row.tenantId(),
                    row.scopes(),
                    row.active(),
                    row.firstParty(),
                    row.publicClient());
        }
    }
}
