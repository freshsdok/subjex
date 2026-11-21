package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.security.OperatorPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * EndUserPrincipals — resolve {@link EndUserPrincipal} and reject operator / anonymous callers.
 * 解析终端主体；操作员或匿名调用一律拒绝。
 */
public final class EndUserPrincipals {

    private EndUserPrincipals() {}

    /**
     * Current end-user principal, or {@link EndUserPhoneAuthService.EndUserAuthenticationRequiredException}.
     * 当前终端主体；否则抛出需终端认证异常。
     */
    public static EndUserPrincipal requireEndUserPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        // Operator tokens / HTTP Basic / OP JWT must not act as end-user authenticators.
        // 操作员令牌 / HTTP Basic / OP JWT 不得作为终端认证器。
        if (principal instanceof OperatorPrincipal) {
            throw new EndUserPhoneAuthService.EndUserAuthenticationRequiredException();
        }
        if (principal instanceof EndUserPrincipal endUser) {
            return endUser;
        }
        throw new EndUserPhoneAuthService.EndUserAuthenticationRequiredException();
    }
}
