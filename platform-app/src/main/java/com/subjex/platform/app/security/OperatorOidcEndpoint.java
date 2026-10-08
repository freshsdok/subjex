package com.subjex.platform.app.security;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.contract.audit.AuditOutcome;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorOidcEndpoint — OIDC 登录 JSON：状态、开始（PKCE）、回调换平台 Bearer。
 * <p>
 * Anonymous. Same access/refresh shape as password login (Slice C). Local TOTP is skipped on this path
 * (IdP MFA is trusted when SSO is used). Unlinked IdP users get {@code reason=oidc-unlinked}.
 * 匿名可访问。令牌形状与口令登录相同。此路径不走本地 TOTP（信任 IdP MFA）。未绑定返回 oidc-unlinked。
 */
@RestController
public class OperatorOidcEndpoint {

    public static final String PATH = JsonApi.BASE + "/auth/oidc";

    private final OidcProperties properties;
    private final OperatorOidcService oidcService;
    private final OperatorActionAudit audit;

    public OperatorOidcEndpoint(
            OidcProperties properties, OperatorOidcService oidcService, OperatorActionAudit audit) {
        this.properties = properties;
        this.oidcService = oidcService;
        this.audit = audit;
    }

    @GetMapping(PATH + "/status")
    public StatusResponse status() {
        return new StatusResponse(properties.ready());
    }

    @PostMapping(PATH + "/start")
    public ResponseEntity<?> start() {
        if (!properties.ready()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("reason", "oidc-disabled"));
        }
        try {
            OperatorOidcService.AuthorizationStart started = oidcService.beginLogin();
            return ResponseEntity.ok(new StartResponse(started.authorizationUrl(), started.state()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("reason", ex.getMessage() == null ? "oidc-unavailable" : ex.getMessage()));
        }
    }

    @PostMapping(PATH + "/callback")
    public ResponseEntity<?> callback(@RequestBody CallbackRequest body, HttpServletRequest request) {
        if (!properties.ready()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("reason", "oidc-disabled"));
        }
        String code = body == null ? null : body.code();
        String state = body == null ? null : body.state();
        try {
            OperatorOidcService.LoginResult result =
                    oidcService.completeLogin(code, state, request.getHeader("User-Agent"), request.getRemoteAddr());
            audit.record(
                    result.operator(),
                    "operator.auth.login.oidc",
                    result.operator().getUsername(),
                    AuditOutcome.ALLOWED);
            JdbcOperatorTokenStore.IssuedTokens issued = result.tokens();
            return ResponseEntity.ok(new OperatorAuthEndpoint.TokenResponse(
                    issued.accessToken(), issued.refreshToken(), issued.expiresInSeconds(), "Bearer"));
        } catch (OidcUnlinkedException ex) {
            Map<String, String> bodyMap = new LinkedHashMap<>();
            bodyMap.put("reason", "oidc-unlinked");
            bodyMap.put("issuer", ex.issuer() == null ? "" : ex.issuer());
            bodyMap.put("idpSubject", ex.idpSubject() == null ? "" : ex.idpSubject());
            if (ex.email() != null && !ex.email().isBlank()) {
                bodyMap.put("email", ex.email());
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(bodyMap);
        } catch (InvalidOperatorTokenException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("reason", ex.getMessage() == null ? "oidc-invalid" : ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("reason", ex.getMessage() == null ? "oidc-unavailable" : ex.getMessage()));
        }
    }

    public record StatusResponse(boolean enabled) {}

    public record StartResponse(String authorizationUrl, String state) {}

    public record CallbackRequest(String code, String state) {}
}
