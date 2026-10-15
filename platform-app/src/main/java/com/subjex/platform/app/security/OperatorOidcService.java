package com.subjex.platform.app.security;

import com.subjex.platform.app.idp.GenericOidcIdpAdapter;
import com.subjex.platform.app.idp.IdpAuthStart;
import com.subjex.platform.app.idp.IdpCallbackRequest;
import com.subjex.platform.app.idp.IdpLoginOrchestrator;
import com.subjex.platform.app.idp.IdpUnlinkedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * OperatorOidcService — OIDC login via {@link GenericOidcIdpAdapter} (ID-4).
 * Keeps Slice E API; IdP adapter SPI owns start/callback identity extraction.
 * 经 GenericOidcIdpAdapter 的 OIDC 登录；SPI 负责提取外部身份。
 */
public final class OperatorOidcService {

    private final OidcProperties properties;
    private final GenericOidcIdpAdapter adapter;
    private final IdpLoginOrchestrator orchestrator;

    public OperatorOidcService(
            OidcProperties properties, GenericOidcIdpAdapter adapter, IdpLoginOrchestrator orchestrator) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    public boolean enabled() {
        return properties.ready();
    }

    public AuthorizationStart beginLogin() {
        requireReady();
        IdpAuthStart started = adapter.startAuth();
        return new AuthorizationStart(started.authorizationUrl(), started.state());
    }

    /**
     * Exchange code+state for platform tokens, or throw {@link OidcUnlinkedException} / invalid token.
     * 用 code+state 换平台令牌；未绑定抛 {@link OidcUnlinkedException}。
     */
    public LoginResult completeLogin(String code, String state, String userAgent, String clientIp) {
        requireReady();
        try {
            IdpLoginOrchestrator.LoginResult result =
                    orchestrator.complete(GenericOidcIdpAdapter.ID, IdpCallbackRequest.oauth(code, state), userAgent, clientIp);
            return new LoginResult(result.operator(), result.tokens());
        } catch (IdpUnlinkedException ex) {
            throw new OidcUnlinkedException(ex.issuer(), ex.idpSubject(), ex.email());
        }
    }

    private void requireReady() {
        if (!properties.ready()) {
            throw new IllegalStateException("oidc-disabled");
        }
    }

    public static String s256Challenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 required", ex);
        }
    }

    public record AuthorizationStart(String authorizationUrl, String state) {}

    public record LoginResult(OperatorPrincipal operator, JdbcOperatorTokenStore.IssuedTokens tokens) {}
}
