package com.subjex.platform.app.idp;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.InvalidOperatorTokenException;
import com.subjex.platform.app.security.JdbcOperatorTokenStore;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorAuthEndpoint;
import com.subjex.platform.contract.audit.AuditOutcome;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * IdpProvidersEndpoint — list login methods + unified IdP start/callback (ID-4).
 * 登录方式列表与统一 IdP 启动/回调。
 */
@RestController
public class IdpProvidersEndpoint {

    public static final String PATH = JsonApi.BASE + "/auth/idp";

    private final IdpAdapterRegistry registry;
    private final IdpLoginOrchestrator orchestrator;
    private final OperatorActionAudit audit;

    public IdpProvidersEndpoint(
            IdpAdapterRegistry registry, IdpLoginOrchestrator orchestrator, OperatorActionAudit audit) {
        this.registry = registry;
        this.orchestrator = orchestrator;
        this.audit = audit;
    }

    @GetMapping(PATH + "/providers")
    public ProvidersResponse providers() {
        List<ProviderDocument> list = new ArrayList<>();
        list.add(new ProviderDocument("local", "password", true, "Local password"));
        for (IdpAdapter adapter : registry.all()) {
            list.add(new ProviderDocument(adapter.id(), adapter.type(), adapter.enabled(), adapter.id()));
        }
        return new ProvidersResponse(list);
    }

    @PostMapping(PATH + "/{providerId}/start")
    public ResponseEntity<?> start(@PathVariable String providerId) {
        try {
            IdpAuthStart started = orchestrator.start(providerId);
            return ResponseEntity.ok(new StartResponse(started.authorizationUrl(), started.state()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("reason", "idp-unknown"));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("reason", ex.getMessage() == null ? "idp-disabled" : ex.getMessage()));
        }
    }

    @PostMapping(PATH + "/{providerId}/callback")
    public ResponseEntity<?> callback(
            @PathVariable String providerId, @RequestBody CallbackBody body, HttpServletRequest request) {
        IdpCallbackRequest cb = new IdpCallbackRequest(
                body == null ? null : body.code(),
                body == null ? null : body.state(),
                body == null ? null : body.jsCode());
        try {
            IdpLoginOrchestrator.LoginResult result =
                    orchestrator.complete(providerId, cb, request.getHeader("User-Agent"), request.getRemoteAddr());
            audit.record(
                    result.operator(),
                    "operator.auth.login.idp." + providerId,
                    result.operator().getUsername(),
                    AuditOutcome.ALLOWED);
            JdbcOperatorTokenStore.IssuedTokens issued = result.tokens();
            return ResponseEntity.ok(new OperatorAuthEndpoint.TokenResponse(
                    issued.accessToken(), issued.refreshToken(), issued.expiresInSeconds(), "Bearer"));
        } catch (IdpUnlinkedException ex) {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("reason", "idp-unlinked");
            map.put("providerId", ex.providerId() == null ? "" : ex.providerId());
            map.put("issuer", ex.issuer() == null ? "" : ex.issuer());
            map.put("idpSubject", ex.idpSubject() == null ? "" : ex.idpSubject());
            if (ex.email() != null && !ex.email().isBlank()) {
                map.put("email", ex.email());
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(map);
        } catch (InvalidOperatorTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("reason", ex.getMessage() == null ? "idp-invalid" : ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("reason", "idp-unknown"));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("reason", ex.getMessage() == null ? "idp-unavailable" : ex.getMessage()));
        }
    }

    public record ProvidersResponse(List<ProviderDocument> providers) {}

    public record ProviderDocument(String id, String type, boolean enabled, String label) {}

    public record StartResponse(String authorizationUrl, String state) {}

    public record CallbackBody(String code, String state, String jsCode) {}
}
