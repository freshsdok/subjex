package com.subjex.platform.app.identity.enduser;

/**
 * EndUserLoginLockoutException — end-user phone login is temporarily locked (HTTP 429, {@code reason=login-lockout}).
 * 终端手机号登录暂时锁定（429，reason 与操作员相同：login-lockout）。
 */
public final class EndUserLoginLockoutException extends RuntimeException {

    public static final String REASON = "login-lockout";

    public EndUserLoginLockoutException(String lockoutKey) {
        super(REASON + ":" + lockoutKey);
    }
}
