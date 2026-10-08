package com.subjex.platform.app.security;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * OidcProperties — OIDC 依赖方配置：默认关闭；开启后需 issuer / client / redirect。
 * <p>
 * Platform is the RP (Authorization Code + PKCE). External IdP authenticates; roles and tenant grants
 * stay in the platform DB. Unlinked IdP subjects are refused (no auto-provision).
 * 平台作为依赖方。外部 IdP 负责认证；角色与租户授权仍在平台库。未绑定的 IdP 主体一律拒绝。
 */
public final class OidcProperties {

    private final boolean enabled;
    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final List<String> scopes;
    private final Duration loginTtl;

    public OidcProperties(
            boolean enabled,
            String issuer,
            String clientId,
            String clientSecret,
            String redirectUri,
            List<String> scopes,
            Duration loginTtl) {
        this.enabled = enabled;
        this.issuer = issuer == null ? "" : issuer.trim();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
        this.scopes = scopes == null || scopes.isEmpty()
                ? List.of("openid", "profile", "email")
                : List.copyOf(scopes);
        this.loginTtl = Objects.requireNonNullElse(loginTtl, Duration.ofMinutes(10));
    }

    public boolean enabled() {
        return enabled;
    }

    public String issuer() {
        return issuer;
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String redirectUri() {
        return redirectUri;
    }

    public List<String> scopes() {
        return scopes;
    }

    public Duration loginTtl() {
        return loginTtl;
    }

    /** True when enabled and the minimum client settings are present — 已开启且关键客户端配置齐全。 */
    public boolean ready() {
        return enabled
                && !issuer.isBlank()
                && !clientId.isBlank()
                && !clientSecret.isBlank()
                && !redirectUri.isBlank();
    }
}
