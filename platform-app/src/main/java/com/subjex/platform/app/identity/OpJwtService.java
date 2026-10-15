package com.subjex.platform.app.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * OpJwtService — mint/verify OP access + id tokens (RS256) (ID-1/ID-2).
 * 签发/校验 OP access、id_token 与 client_credentials JWT（RS256）。
 */
public final class OpJwtService {

    public static final String CLAIM_TOKEN_USE = "token_use";
    public static final String CLAIM_LOGIN = "preferred_username";
    public static final String CLAIM_CLIENT_ID = "client_id";
    public static final String CLAIM_TENANT_ID = "tenant_id";
    public static final String CLAIM_SCOPE = "scope";

    private final OpProperties properties;
    private final OpSigningKeyService keys;

    public OpJwtService(OpProperties properties, OpSigningKeyService keys) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    public String mintAccessToken(OperatorPrincipal operator, String clientId, String scopes) {
        Instant now = Instant.now();
        Instant exp = now.plus(properties.accessTokenTtl());
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(properties.issuer())
                    .subject(operator.subjectId())
                    .audience(clientId)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(exp))
                    .jwtID(UUID.randomUUID().toString())
                    .claim(CLAIM_TOKEN_USE, "access")
                    .claim(CLAIM_LOGIN, operator.getUsername())
                    .claim(CLAIM_CLIENT_ID, clientId)
                    .claim(CLAIM_SCOPE, scopes == null ? "" : scopes)
                    .build();
            return sign(claims);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot mint access token", ex);
        }
    }

    /**
     * Mint client_credentials access JWT (no human subject) — 签发服务账号 access JWT（无人工主体）。
     */
    public String mintClientCredentialsToken(OauthClientRecord client, String grantedScopes) {
        Instant now = Instant.now();
        Instant exp = now.plus(properties.accessTokenTtl());
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(properties.issuer())
                    .subject(client.clientId())
                    .audience(client.clientId())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(exp))
                    .jwtID(UUID.randomUUID().toString())
                    .claim(CLAIM_TOKEN_USE, "access")
                    .claim(CLAIM_CLIENT_ID, client.clientId())
                    .claim(CLAIM_TENANT_ID, client.tenantId())
                    .claim(CLAIM_SCOPE, grantedScopes == null ? "" : grantedScopes)
                    .build();
            return sign(claims);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot mint client_credentials token", ex);
        }
    }

    public String mintIdToken(OperatorPrincipal operator, String clientId, String nonce) {
        Instant now = Instant.now();
        Instant exp = now.plus(properties.idTokenTtl());
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(properties.issuer())
                    .subject(operator.subjectId())
                    .audience(clientId)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(exp))
                    .jwtID(UUID.randomUUID().toString())
                    .claim(CLAIM_TOKEN_USE, "id")
                    .claim(CLAIM_LOGIN, operator.getUsername());
            if (nonce != null && !nonce.isBlank()) {
                builder.claim("nonce", nonce);
            }
            return sign(builder.build());
        } catch (Exception ex) {
            throw new IllegalStateException("cannot mint id token", ex);
        }
    }

    /**
     * Verify access JWT and return claims if valid — 校验 access JWT，有效则返回声明。
     */
    public Optional<JWTClaimsSet> verifyAccessToken(String rawJwt) {
        if (rawJwt == null || rawJwt.isBlank() || rawJwt.chars().filter(c -> c == '.').count() != 2) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(rawJwt.trim());
            if (!jwt.verify(new RSASSAVerifier(keys.publicKey()))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!properties.issuer().equals(claims.getIssuer())) {
                return Optional.empty();
            }
            Date exp = claims.getExpirationTime();
            if (exp == null || !exp.toInstant().isAfter(Instant.now())) {
                return Optional.empty();
            }
            Object use = claims.getClaim(CLAIM_TOKEN_USE);
            if (use != null && !"access".equals(String.valueOf(use))) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.kid()).build(), claims);
        jwt.sign(new RSASSASigner(keys.privateKey()));
        return jwt.serialize();
    }
}
