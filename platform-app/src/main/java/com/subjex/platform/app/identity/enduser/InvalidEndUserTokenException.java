package com.subjex.platform.app.identity.enduser;

/**
 * InvalidEndUserTokenException — end-user access/refresh token is invalid or revoked (maps to 401).
 * 终端访问/刷新令牌无效或已吊销（映射为 401）。
 */
public final class InvalidEndUserTokenException extends RuntimeException {

    public InvalidEndUserTokenException(String message) {
        super(message);
    }
}
