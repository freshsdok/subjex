package com.subjex.platform.app.identity.enduser;

/**
 * LastAuthenticatorException — unbind refused because it would leave the account with no sign-in method.
 * 拒绝解绑：这将使账号没有任何登录方式（HTTP 409，{@code reason=last-authenticator}）。
 */
public final class LastAuthenticatorException extends RuntimeException {

    public static final String REASON = "last-authenticator";

    public LastAuthenticatorException() {
        super(REASON);
    }
}
