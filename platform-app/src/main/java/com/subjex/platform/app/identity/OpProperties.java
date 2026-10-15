package com.subjex.platform.app.identity;

import java.time.Duration;
import java.util.Objects;

/**
 * OpProperties — ID-1 thin OIDC OP settings (issuer, TTLs, console client id).
 * ID-1 轻量 OP 配置：签发方、令牌 TTL、控制台 client_id。
 */
public final class OpProperties {

    public static final String DEFAULT_CONSOLE_CLIENT_ID = "subjex-console";

    private final String issuer;
    private final Duration accessTokenTtl;
    private final Duration idTokenTtl;
    private final Duration authCodeTtl;
    private final String consoleClientId;

    public OpProperties(
            String issuer,
            Duration accessTokenTtl,
            Duration idTokenTtl,
            Duration authCodeTtl,
            String consoleClientId) {
        this.issuer = Objects.requireNonNull(issuer, "issuer").trim();
        if (this.issuer.isBlank()) {
            throw new IllegalArgumentException("platform.op.issuer required");
        }
        this.accessTokenTtl = Objects.requireNonNullElse(accessTokenTtl, Duration.ofMinutes(30));
        this.idTokenTtl = Objects.requireNonNullElse(idTokenTtl, Duration.ofMinutes(30));
        this.authCodeTtl = Objects.requireNonNullElse(authCodeTtl, Duration.ofMinutes(5));
        this.consoleClientId =
                consoleClientId == null || consoleClientId.isBlank()
                        ? DEFAULT_CONSOLE_CLIENT_ID
                        : consoleClientId.trim();
    }

    public String issuer() {
        return issuer;
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }

    public Duration idTokenTtl() {
        return idTokenTtl;
    }

    public Duration authCodeTtl() {
        return authCodeTtl;
    }

    public String consoleClientId() {
        return consoleClientId;
    }
}
