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
 * EndUserPhoneAuthEndpoint — phone request-code / register-or-login / bind / unbind (WP2-3).
 * 手机号请求验证码、注册或登录、绑定与解绑。
 * <p>
 * Paths live under {@link EndUserAuthEndpoint#PATH}. Operator tokens and HTTP Basic are not
 * end-user authenticators: bind/unbind require {@link EndUserPrincipal}.
 * 路径挂在终端认证前缀下。操作员令牌与 HTTP Basic 不是终端认证器。
 */
@RestController
public class EndUserPhoneAuthEndpoint {

    public static final String PATH = EndUserAuthEndpoint.PATH + "/phone";

    private final EndUserPhoneAuthService phoneAuth;

    public EndUserPhoneAuthEndpoint(EndUserPhoneAuthService phoneAuth) {
        this.phoneAuth = Objects.requireNonNull(phoneAuth, "phoneAuth");
    }

    @PostMapping(PATH + "/request-code")
    public ResponseEntity<?> requestCode(@RequestBody(required = false) RequestCodeRequest body) {
        try {
            String phone = body == null ? null : body.phone();
            String purpose = body == null ? null : body.purpose();
            VerificationCodePort.IssuedCode issued = phoneAuth.requestCode(phone, purpose);
            RequestCodeResponse response = new RequestCodeResponse(
                    issued.expiresInSeconds(), issued.purpose(), issued.devCode());
            return ResponseEntity.ok(response);
        } catch (EndUserLoginLockoutException ex) {
            return lockoutResponse();
        } catch (VerificationCodeSenderUnconfiguredException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("reason", VerificationCodeSenderUnconfiguredException.REASON));
        } catch (VerificationCodeSendFailedException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("reason", VerificationCodeSendFailedException.REASON));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("reason", ex.getMessage()));
        }
    }

    @PostMapping(PATH + "/login")
    public ResponseEntity<?> loginOrRegister(
            @RequestBody(required = false) PhoneLoginRequest body, HttpServletRequest request) {
        try {
            String phone = body == null ? null : body.phone();
            String code = body == null ? null : body.code();
            String tenantId = EndUserPhoneAuthService.resolveTenantId(
                    body == null ? null : body.tenantId(),
                    request.getHeader(TenantEnforcementFilter.TENANT_HEADER));
            JdbcEndUserTokenStore.IssuedTokens issued =
                    phoneAuth.loginOrRegister(
                            phone, code, tenantId, request.getHeader("User-Agent"), request.getRemoteAddr());
            return ResponseEntity.ok(EndUserAuthEndpoint.toPublicResponse(issued));
        } catch (EndUserLoginLockoutException ex) {
            return lockoutResponse();
        } catch (InvalidVerificationCodeException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("reason", "invalid-verification-code"));
        } catch (TenantMissingException | TenantDisabledException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (TenantNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("reason", ex.reason()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("reason", ex.getMessage()));
        }
    }

    @PostMapping(PATH + "/bind")
    public ResponseEntity<?> bind(@RequestBody(required = false) PhoneCodeRequest body) {
        try {
            EndUserPrincipal actor = requireEndUserPrincipal();
            phoneAuth.bind(actor, body == null ? null : body.phone(), body == null ? null : body.code());
            return ResponseEntity.noContent().build();
        } catch (EndUserPhoneAuthService.EndUserAuthenticationRequiredException ex) {
            return forbiddenEndUserOnly();
        } catch (InvalidVerificationCodeException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("reason", "invalid-verification-code"));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("phone already bound")) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("reason", "phone-already-bound"));
            }
            return ResponseEntity.badRequest().body(Map.of("reason", ex.getMessage()));
        }
    }

    @PostMapping(PATH + "/unbind")
    public ResponseEntity<?> unbind(@RequestBody(required = false) PhoneCodeRequest body) {
        try {
            EndUserPrincipal actor = requireEndUserPrincipal();
            phoneAuth.unbind(actor, body == null ? null : body.phone(), body == null ? null : body.code());
            return ResponseEntity.noContent().build();
        } catch (EndUserPhoneAuthService.EndUserAuthenticationRequiredException ex) {
            return forbiddenEndUserOnly();
        } catch (InvalidVerificationCodeException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("reason", "invalid-verification-code"));
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
        // Operator tokens / HTTP Basic / OP JWT must not act as an end-user authenticator.
        // 操作员令牌 / HTTP Basic / OP JWT 不得作为终端认证器。
        if (principal instanceof OperatorPrincipal) {
            throw new EndUserPhoneAuthService.EndUserAuthenticationRequiredException();
        }
        if (principal instanceof EndUserPrincipal endUser) {
            return endUser;
        }
        throw new EndUserPhoneAuthService.EndUserAuthenticationRequiredException();
    }

    private static ResponseEntity<Map<String, String>> lockoutResponse() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("reason", EndUserLoginLockoutException.REASON));
    }

    private static ResponseEntity<Map<String, String>> forbiddenEndUserOnly() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("reason", EndUserPhoneAuthService.EndUserAuthenticationRequiredException.REASON));
    }

    public record RequestCodeRequest(String phone, String purpose) {}

    public record RequestCodeResponse(long expiresIn, String purpose, String devCode) {}

    public record PhoneLoginRequest(String phone, String code, String tenantId) {}

    public record PhoneCodeRequest(String phone, String code) {}
}
