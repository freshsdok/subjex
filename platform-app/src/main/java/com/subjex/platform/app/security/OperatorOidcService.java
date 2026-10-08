package com.subjex.platform.app.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * OperatorOidcService — OIDC 登录编排：开 PKCE、换码取声明、查绑定、签发平台令牌。
 * <p>
 * OIDC path does <strong>not</strong> prompt for local TOTP: the IdP is trusted for MFA when SSO is used.
 * Password login still enforces enrolled TOTP (Slice D). Unlinked IdP subjects are refused.
 * OIDC 路径不二次校验本地 TOTP（信任 IdP 侧 MFA）。口令登录仍走 Slice D。未绑定一律拒绝。
 */
public final class OperatorOidcService {

    private final OidcProperties properties;
    private final JdbcOidcLoginStore loginStore;
    private final OidcTokenClient tokenClient;
    private final JdbcOperatorIdpLinkStore linkStore;
    private final JdbcOperatorDirectory directory;
    private final JdbcOperatorTokenStore tokenStore;

    public OperatorOidcService(
            OidcProperties properties,
            JdbcOidcLoginStore loginStore,
            OidcTokenClient tokenClient,
            JdbcOperatorIdpLinkStore linkStore,
            JdbcOperatorDirectory directory,
            JdbcOperatorTokenStore tokenStore) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.loginStore = Objects.requireNonNull(loginStore, "loginStore");
        this.tokenClient = Objects.requireNonNull(tokenClient, "tokenClient");
        this.linkStore = Objects.requireNonNull(linkStore, "linkStore");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
    }

    public boolean enabled() {
        return properties.ready();
    }

    public AuthorizationStart beginLogin() {
        requireReady();
        OidcPendingLogin pending = loginStore.create();
        String authorizationEndpoint = resolveAuthorizationEndpoint();
        String scope = properties.scopes().stream().collect(Collectors.joining(" "));
        String url = authorizationEndpoint
                + "?response_type=code"
                + "&client_id=" + enc(properties.clientId())
                + "&redirect_uri=" + enc(properties.redirectUri())
                + "&scope=" + enc(scope)
                + "&state=" + enc(pending.state())
                + "&nonce=" + enc(pending.nonce())
                + "&code_challenge=" + enc(s256Challenge(pending.codeVerifier()))
                + "&code_challenge_method=S256";
        return new AuthorizationStart(url, pending.state());
    }

    /**
     * Exchange code+state for platform tokens, or throw {@link OidcUnlinkedException} / invalid token.
     * 用 code+state 换平台令牌；未绑定抛 {@link OidcUnlinkedException}。
     */
    public LoginResult completeLogin(String code, String state, String userAgent, String clientIp) {
        requireReady();
        OidcPendingLogin pending = loginStore
                .consume(state)
                .orElseThrow(() -> new InvalidOperatorTokenException("oidc-state-invalid"));
        OidcClaims claims = tokenClient.redeemAuthorizationCode(pending, code);
        String issuer = HttpOidcTokenClient.normalizeIssuer(claims.issuer());
        JdbcOperatorIdpLinkStore.LinkedOperator link = linkStore
                .findByIssuerAndSubject(issuer, claims.subject())
                .orElseThrow(() -> new OidcUnlinkedException(issuer, claims.subject(), claims.email()));
        OperatorPrincipal operator;
        try {
            operator = (OperatorPrincipal) directory.loadUserByUsername(link.loginName());
        } catch (UsernameNotFoundException ex) {
            throw new OidcUnlinkedException(issuer, claims.subject(), claims.email());
        }
        if (!operator.isEnabled()) {
            throw new InvalidOperatorTokenException("operator-disabled");
        }
        JdbcOperatorTokenStore.IssuedTokens issued = tokenStore.issue(operator, userAgent, clientIp);
        return new LoginResult(operator, issued);
    }

    private void requireReady() {
        if (!properties.ready()) {
            throw new IllegalStateException("oidc-disabled");
        }
    }

    private String resolveAuthorizationEndpoint() {
        if (tokenClient instanceof HttpOidcTokenClient http) {
            return http.discovery().authorizationEndpoint();
        }
        // Stub clients in tests supply a fixed authorize URL via properties issuer + path convention,
        // or tests call completeLogin only. For beginLogin with stubs, use issuer + /authorize.
        // 测试桩：beginLogin 用 issuer + /authorize；多数测试只测 completeLogin。
        String issuer = HttpOidcTokenClient.normalizeIssuer(properties.issuer());
        return issuer + "/protocol/openid-connect/auth";
    }

    static String s256Challenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 required", ex);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record AuthorizationStart(String authorizationUrl, String state) {}

    public record LoginResult(OperatorPrincipal operator, JdbcOperatorTokenStore.IssuedTokens tokens) {}
}
