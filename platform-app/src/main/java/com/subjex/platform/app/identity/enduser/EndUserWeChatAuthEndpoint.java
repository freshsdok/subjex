package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.tenant.TenantMissingException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * EndUserWeChatAuthEndpoint — WeChat mini-program login / bind / unbind (WP2-4 / ADR 0014).
 * 微信小程序登录、绑定与解绑。
 * <p>
 * Paths live under {@link EndUserAuthEndpoint#PATH}. This is not the operator
 * {@code POST /api/v1/auth/idp/wechat/callback} (unlinked = deny). Operator tokens and HTTP Basic
 * are not end-user authenticators.
 * 不是操作员微信回调。操作员令牌与 HTTP Basic 不是终端认证器。
 */
@RestController
public class EndUserWeChatAuthEndpoint {

    public static final String PATH = EndUserAuthEndpoint.PATH + "/wechat";

    private final EndUserWeChatAuthService weChatAuth;

    public EndUserWeChatAuthEndpoint(EndUserWeChatAuthService weChatAuth) {
        this.weChatAuth = Objects.requireNonNull(weChatAuth, "weChatAuth");
    }

    @PostMapping(PATH + "/login")
    public ResponseEntity<?> login(
            @RequestBody(required = false) WeChatLoginRequest body, HttpServletRequest request) {
        try {
            String jsCode = body == null ? null : body.jsCode();
            String tenantId = EndUserPhoneAuthService.resolveTenantId(
                    body == null ? null : body.tenantId(),
                    request.getHeader(TenantEnforcementFilter.TENANT_HEADER));
            JdbcEndUserTokenStore.IssuedTokens issued = weChatAuth.login(
                    jsCode, tenantId, request.getHeader("User-Agent"), request.getRemoteAddr());
            return ResponseEntity.ok(EndUserAuthEndpoint.toPublicResponse(issued));
        } catch (WeChatUnconfiguredException ex) {
            return unconfigured();
        } catch (EndUserLoginLockoutException ex) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("reason", EndUserLoginLockoutException.REASON));
        } catch (InvalidEndUserWeChatException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("reason", InvalidEndUserWeChatException.REASON));
        } catch (TenantMissingException | TenantDisabledException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (TenantNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", ex.reason()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("reason", ex.getMessage()));
        }
    }

    @PostMapping(PATH + "/bind")
    public ResponseEntity<?> bind(@RequestBody(required = false) WeChatBindRequest body) {
        try {
            EndUserPrincipal actor = requireEndUserPrincipal();
            weChatAuth.bind(actor, body == null ? null : body.jsCode());
            return ResponseEntity.noContent().build();
        } catch (EndUserPhoneAuthService.EndUserAuthenticationRequiredException ex) {
            return forbiddenEndUserOnly();
        } catch (WeChatUnconfiguredException ex) {
            return unconfigured();
        } catch (InvalidEndUserWeChatException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("reason", InvalidEndUserWeChatException.REASON));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("idp subject already bound")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("reason", "idp-already-bound"));
            }
            return ResponseEntity.badRequest().body(Map.of("reason", ex.getMessage()));
        }
    }

    @PostMapping(PATH + "/unbind")
    public ResponseEntity<?> unbind(@RequestBody(required = false) WeChatUnbindRequest body) {
        try {
            EndUserPrincipal actor = requireEndUserPrincipal();
            weChatAuth.unbind(
                    actor,
                    body == null ? null : body.jsCode(),
                    body == null ? null : body.issuer(),
                    body == null ? null : body.idpSubject());
            return ResponseEntity.noContent().build();
        } catch (EndUserPhoneAuthService.EndUserAuthenticationRequiredException ex) {
            return forbiddenEndUserOnly();
        } catch (WeChatUnconfiguredException ex) {
            return unconfigured();
        } catch (InvalidEndUserWeChatException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("reason", InvalidEndUserWeChatException.REASON));
        } catch (LastAuthenticatorException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("reason", LastAuthenticatorException.REASON));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("reason", ex.getMessage()));
        }
    }

    private static EndUserPrincipal requireEndUserPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new EndUserPhoneAuthService.EndUserAuthenticationRequiredException();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof OperatorPrincipal) {
            throw new EndUserPhoneAuthService.EndUserAuthenticationRequiredException();
        }
        if (principal instanceof EndUserPrincipal endUser) {
            return endUser;
        }
        throw new EndUserPhoneAuthService.EndUserAuthenticationRequiredException();
    }

    private static ResponseEntity<Map<String, String>> unconfigured() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("reason", WeChatUnconfiguredException.REASON));
    }

    private static ResponseEntity<Map<String, String>> forbiddenEndUserOnly() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", EndUserPhoneAuthService.EndUserAuthenticationRequiredException.REASON));
    }

    public record WeChatLoginRequest(String jsCode, String tenantId) {}

    public record WeChatBindRequest(String jsCode) {}

    public record WeChatUnbindRequest(String jsCode, String issuer, String idpSubject) {}
}
