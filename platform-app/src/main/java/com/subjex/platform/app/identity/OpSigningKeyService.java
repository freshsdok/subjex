package com.subjex.platform.app.identity;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * OpSigningKeyService — RS256 signing key for OP JWTs; persists PEM, exposes JWKS (ID-1).
 * OP JWT 的 RS256 签名密钥；PEM 入库，对外暴露 JWKS。
 */
public final class OpSigningKeyService {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile RSAKey activeKey;

    public OpSigningKeyService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Active RSA key (generate + persist if none) — 当前活动密钥（无则生成入库）。 */
    public synchronized RSAKey activeRsaKey() {
        if (activeKey != null) {
            return activeKey;
        }
        List<RSAKey> loaded = loadActiveFromDb();
        if (!loaded.isEmpty()) {
            activeKey = loaded.get(0);
            return activeKey;
        }
        try {
            String kid = "op-" + UUID.randomUUID();
            RSAKey generated = new RSAKeyGenerator(2048).keyID(kid).generate();
            persist(generated);
            activeKey = generated;
            return activeKey;
        } catch (Exception ex) {
            throw new IllegalStateException("cannot generate OP signing key", ex);
        }
    }

    /** Public JWKS JSON — 公钥 JWKS JSON。 */
    public String jwksJson() {
        RSAKey pub = activeRsaKey().toPublicJWK();
        return new JWKSet(pub).toString();
    }

    public RSAPrivateKey privateKey() {
        try {
            return activeRsaKey().toRSAPrivateKey();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot export private key", ex);
        }
    }

    public RSAPublicKey publicKey() {
        try {
            return activeRsaKey().toRSAPublicKey();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot export public key", ex);
        }
    }

    public String kid() {
        return activeRsaKey().getKeyID();
    }

    private List<RSAKey> loadActiveFromDb() {
        return jdbc.query(
                """
                SELECT kid, private_pem, public_pem
                  FROM oauth_signing_key
                 WHERE active = TRUE
                 ORDER BY created_at DESC
                """,
                (row, n) -> {
                    try {
                        // Parse from public+private PEM via Nimbus RSAKey.parse on JWK JSON is easier —
                        // we store PEMs; rebuild RSAKey.
                        // 用 PEM 重建 RSAKey。
                        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
                        String privatePem = row.getString("private_pem");
                        String publicPem = row.getString("public_pem");
                        RSAPrivateKey priv = (RSAPrivateKey)
                                kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(pemDecode(privatePem)));
                        RSAPublicKey pub = (RSAPublicKey)
                                kf.generatePublic(new java.security.spec.X509EncodedKeySpec(pemDecode(publicPem)));
                        return new RSAKey.Builder(pub).privateKey(priv).keyID(row.getString("kid")).build();
                    } catch (Exception ex) {
                        throw new IllegalStateException("cannot load signing key", ex);
                    }
                });
    }

    private void persist(RSAKey key) throws Exception {
        String kid = key.getKeyID();
        String privatePem = pemEncode("PRIVATE KEY", key.toRSAPrivateKey().getEncoded());
        String publicPem = pemEncode("PUBLIC KEY", key.toRSAPublicKey().getEncoded());
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("UPDATE oauth_signing_key SET active = FALSE WHERE active = TRUE");
        jdbc.update(
                """
                INSERT INTO oauth_signing_key (kid, algorithm, private_pem, public_pem, active, created_at)
                VALUES (?, 'RS256', ?, ?, TRUE, ?)
                """,
                kid,
                privatePem,
                publicPem,
                now);
    }

    private static byte[] pemDecode(String pem) {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return java.util.Base64.getDecoder().decode(body);
    }

    private static String pemEncode(String type, byte[] der) {
        String b64 = java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----";
    }
}
