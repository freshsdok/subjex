package com.subjex.platform.app.security;

/**
 * LoginLockoutException — 登录暂时锁定：该登录名失败次数达阈，在 locked_until 之前拒绝口令登录。
 * <p>
 * Thrown by {@link JdbcOperatorLoginLockout#assertNotLocked}; mapped to HTTP 429 with
 * {@code reason=login-lockout}. Distinct from 401 wrong-password.
 * 由锁定存储在仍锁定时抛出；映射为 429 与 reason=login-lockout，区别于口令错误的 401。
 */
public final class LoginLockoutException extends RuntimeException {

    public LoginLockoutException(String loginName) {
        super("login-lockout:" + loginName);
    }
}
