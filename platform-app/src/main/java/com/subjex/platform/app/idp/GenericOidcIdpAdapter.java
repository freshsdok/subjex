package com.subjex.platform.app.idp;

import com.subjex.platform.app.security.HttpOidcTokenClient;
import com.subjex.platform.app.security.InvalidOperatorTokenException;
import com.subjex.platform.app.security.JdbcOidcLoginStore;
import com.subjex.platform.app.security.OidcClaims;
import com.subjex.platform.app.security.OidcPendingLogin;
import com.subjex.platform.app.security.OidcProperties;
import com.subjex.platform.app.security.OidcTokenClient;
import com.subjex.platform.app.security.OperatorOidcService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * GenericOidcIdpAdapter — existing {@code platform.oidc.*} RP behind {@link IdpAdapter} (ID-4).
 * 将既有 OIDC 依赖方配置接到 IdP SPI。
 */
public final class GenericOidcIdpAdapter implements IdpAdapter {

    public static final String ID = "generic-oidc";

    private final OidcProperties properties;
    private final JdbcOidcLoginStore loginStore;
    private final OidcTokenClient tokenClient;

    public GenericOidcIdpAdapter(
            OidcProperties properties, JdbcOidcLoginStore loginStore, OidcTokenClient tokenClient) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.loginStore = Objects.requireNonNull(loginStore, "loginStore");
        this.tokenClient = Objects.requireNonNull(tokenClient, "tokenClient");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String type() {
        return "oidc";
    }

    @Override
    public boolean enabled() {
        return properties.ready();
    }

    @Override
    public IdpAuthStart startAuth() {
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
                + "&code_challenge=" + enc(OperatorOidcService.s256Challenge(pending.codeVerifier()))
                + "&code_challenge_method=S256";
        return new IdpAuthStart(url, pending.state());
    }

    @Override
    public IdpExternalIdentity handleCallback(IdpCallbackRequest request) {
        requireReady();
        if (request == null || request.code() == null || request.state() == null) {
            throw new InvalidOperatorTokenException("oidc-code-missing");
        }
        OidcPendingLogin pending = loginStore
                .consume(request.state())
                .orElseThrow(() -> new InvalidOperatorTokenException("oidc-state-invalid"));
        OidcClaims claims = tokenClient.redeemAuthorizationCode(pending, request.code());
        String issuer = HttpOidcTokenClient.normalizeIssuer(claims.issuer());
        return new IdpExternalIdentity(ID, issuer, claims.subject(), claims.email());
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
        String issuer = HttpOidcTokenClient.normalizeIssuer(properties.issuer());
        return issuer + "/protocol/openid-connect/auth";
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
