package com.subjex.platform.app.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HttpOidcTokenClient — 发现文档 + 授权码换令牌 + 校验 ID Token（JWKS），取出 iss/sub。
 * <p>
 * Confidential client: client_secret in the token request. PKCE S256 on authorize + token.
 * 机密客户端：token 请求带 client_secret；授权与换码均使用 PKCE S256。
 */
public final class HttpOidcTokenClient implements OidcTokenClient {

    private final OidcProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final AtomicReference<Discovery> discovery = new AtomicReference<>();

    public HttpOidcTokenClient(OidcProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public OidcClaims redeemAuthorizationCode(OidcPendingLogin pending, String authorizationCode) {
        Objects.requireNonNull(pending, "pending");
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new InvalidOperatorTokenException("oidc-code-missing");
        }
        Discovery meta = discovery();
        JsonNode tokenResponse = postToken(meta.tokenEndpoint(), pending, authorizationCode.trim());
        String idToken = text(tokenResponse, "id_token");
        if (idToken == null || idToken.isBlank()) {
            throw new InvalidOperatorTokenException("oidc-id-token-missing");
        }
        return validateIdToken(idToken, pending.nonce(), meta.jwksUri());
    }

    Discovery discovery() {
        Discovery cached = discovery.get();
        if (cached != null) {
            return cached;
        }
        String issuer = properties.issuer().replaceAll("/$", "");
        String url = issuer + "/.well-known/openid-configuration";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("oidc-discovery-failed:" + response.statusCode());
            }
            JsonNode body = objectMapper.readTree(response.body());
            String discoveredIssuer = text(body, "issuer");
            if (discoveredIssuer == null || !normalizeIssuer(discoveredIssuer).equals(normalizeIssuer(issuer))) {
                throw new IllegalStateException("oidc-issuer-mismatch");
            }
            String authorizationEndpoint = text(body, "authorization_endpoint");
            String tokenEndpoint = text(body, "token_endpoint");
            String jwksUri = text(body, "jwks_uri");
            if (authorizationEndpoint == null || tokenEndpoint == null || jwksUri == null) {
                throw new IllegalStateException("oidc-discovery-incomplete");
            }
            Discovery fresh = new Discovery(normalizeIssuer(discoveredIssuer), authorizationEndpoint, tokenEndpoint, jwksUri);
            discovery.compareAndSet(null, fresh);
            return discovery.get();
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("oidc-discovery-unreachable", ex);
        }
    }

    private JsonNode postToken(String tokenEndpoint, OidcPendingLogin pending, String code) {
        String body = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(properties.redirectUri())
                + "&client_id=" + enc(properties.clientId())
                + "&client_secret=" + enc(properties.clientSecret())
                + "&code_verifier=" + enc(pending.codeVerifier());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("content-type", "application/x-www-form-urlencoded")
                    .header("accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new InvalidOperatorTokenException("oidc-token-exchange-failed");
            }
            return objectMapper.readTree(response.body());
        } catch (IOException ex) {
            throw new InvalidOperatorTokenException("oidc-token-unreachable");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InvalidOperatorTokenException("oidc-token-unreachable");
        }
    }

    private OidcClaims validateIdToken(String idToken, String expectedNonce, String jwksUri) {
        try {
            JWKSource<SecurityContext> jwkSource = JWKSourceBuilder.create(URI.create(jwksUri).toURL())
                    .retrying(true)
                    .build();
            ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            JWSKeySelector<SecurityContext> keySelector = new JWSVerificationKeySelector<>(
                    java.util.Set.of(
                            JWSAlgorithm.RS256,
                            JWSAlgorithm.RS384,
                            JWSAlgorithm.RS512,
                            JWSAlgorithm.PS256,
                            JWSAlgorithm.ES256,
                            JWSAlgorithm.ES384,
                            JWSAlgorithm.ES512),
                    jwkSource);
            processor.setJWSKeySelector(keySelector);
            JWTClaimsSet claims = processor.process(idToken, null);
            String iss = claims.getIssuer();
            if (iss == null || !normalizeIssuer(iss).equals(normalizeIssuer(properties.issuer()))) {
                throw new InvalidOperatorTokenException("oidc-issuer-invalid");
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(properties.clientId())) {
                throw new InvalidOperatorTokenException("oidc-audience-invalid");
            }
            Date exp = claims.getExpirationTime();
            // 60s clock skew allowance — 允许约 60 秒时钟偏差。
            if (exp == null || exp.toInstant().isBefore(java.time.Instant.now().minusSeconds(60))) {
                throw new InvalidOperatorTokenException("oidc-token-expired");
            }
            String nonce = claims.getStringClaim("nonce");
            if (expectedNonce == null || !expectedNonce.equals(nonce)) {
                throw new InvalidOperatorTokenException("oidc-nonce-invalid");
            }
            String sub = claims.getSubject();
            if (sub == null || sub.isBlank()) {
                throw new InvalidOperatorTokenException("oidc-subject-missing");
            }
            String email = claims.getStringClaim("email");
            return new OidcClaims(normalizeIssuer(iss), sub, email);
        } catch (InvalidOperatorTokenException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidOperatorTokenException("oidc-id-token-invalid");
        }
    }

    static String normalizeIssuer(String issuer) {
        String trimmed = issuer.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record Discovery(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri) {}
}
