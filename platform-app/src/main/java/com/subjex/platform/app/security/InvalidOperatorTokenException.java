package com.subjex.platform.app.security;

/**
 * InvalidOperatorTokenException — 访问/刷新令牌无效或已被吊销时抛出（映射为 401）。
 */
public final class InvalidOperatorTokenException extends RuntimeException {

    public InvalidOperatorTokenException(String message) {
        super(message);
    }
}
