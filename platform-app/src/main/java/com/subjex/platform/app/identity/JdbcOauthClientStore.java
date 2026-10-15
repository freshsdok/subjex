package com.subjex.platform.app.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcOauthClientStore — OAuth clients: SA (ID-2) and third-party Auth Code (ID-3).
 * OAuth 客户端：服务账号与第三方授权码客户端。
 */
public final class JdbcOauthClientStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcOauthClientStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public JdbcOauthClientStore(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public Optional<OauthClientRecord> findByClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        List<OauthClientRecord> rows = jdbc.query(
                """
                SELECT client_id, client_secret_hash, client_name, redirect_uris, grant_types, scopes,
                       require_pkce, public_client, active, tenant_id, first_party
                  FROM oauth_client
                 WHERE client_id = ?
                """,
                mapper(),
                clientId.trim());
        return rows.stream().findFirst();
    }

    public List<OauthClientRecord> listByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT client_id, client_secret_hash, client_name, redirect_uris, grant_types, scopes,
                       require_pkce, public_client, active, tenant_id, first_party
                  FROM oauth_client
                 WHERE tenant_id = ?
                 ORDER BY client_id
                """,
                mapper(),
                tenantId.trim());
    }

    /**
     * Create confidential service-account client; returns plaintext secret once.
     * 创建机密服务账号客户端；明文密钥只返回一次。
     */
    public IssuedSecret createServiceAccount(
            String clientId, String clientName, String tenantId, List<String> scopes) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(tenantId, "tenantId");
        String id = clientId.trim();
        String tenant = tenantId.trim();
        if (id.isEmpty() || tenant.isEmpty()) {
            throw new IllegalArgumentException("clientId and tenantId required");
        }
        if (findByClientId(id).isPresent()) {
            throw new IllegalArgumentException("client already exists");
        }
        String scopeRaw = joinScopes(scopes);
        if (scopeRaw.isBlank()) {
            throw new IllegalArgumentException("at least one scope required");
        }
        String rawSecret = newSecret();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                """
                INSERT INTO oauth_client (
                  client_id, client_secret_hash, client_name, redirect_uris, grant_types, scopes,
                  require_pkce, public_client, active, created_at, tenant_id, first_party)
                VALUES (?, ?, ?, '', 'client_credentials', ?, FALSE, FALSE, TRUE, ?, ?, FALSE)
                """,
                id,
                sha256Hex(rawSecret),
                clientName == null || clientName.isBlank() ? id : clientName.trim(),
                scopeRaw,
                now,
                tenant);
        return new IssuedSecret(id, rawSecret);
    }

    /**
     * Create third-party (or first-party) Authorization Code client.
     * 创建第三方（或一等）授权码客户端。Public clients return null secret.
     */
    public IssuedSecret createAuthorizationCodeClient(
            String clientId,
            String clientName,
            List<String> redirectUris,
            List<String> scopes,
            boolean publicClient,
            boolean firstParty) {
        Objects.requireNonNull(clientId, "clientId");
        String id = clientId.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("clientId required");
        }
        if (findByClientId(id).isPresent()) {
            throw new IllegalArgumentException("client already exists");
        }
        String redirects = joinScopes(redirectUris);
        if (redirects.isBlank()) {
            throw new IllegalArgumentException("redirectUris required");
        }
        String scopeRaw = joinScopes(scopes);
        if (scopeRaw.isBlank() || !scopeRaw.contains("openid")) {
            throw new IllegalArgumentException("scopes must include openid");
        }
        String rawSecret = publicClient ? null : newSecret();
        String hash = rawSecret == null ? null : sha256Hex(rawSecret);
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                """
                INSERT INTO oauth_client (
                  client_id, client_secret_hash, client_name, redirect_uris, grant_types, scopes,
                  require_pkce, public_client, active, created_at, tenant_id, first_party)
                VALUES (?, ?, ?, ?, 'authorization_code', ?, TRUE, ?, TRUE, ?, NULL, ?)
                """,
                id,
                hash,
                clientName == null || clientName.isBlank() ? id : clientName.trim(),
                redirects,
                scopeRaw,
                publicClient,
                now,
                firstParty);
        return new IssuedSecret(id, rawSecret);
    }

    public IssuedSecret rotateSecret(String clientId) {
        OauthClientRecord client = findByClientId(clientId)
                .filter(c -> !c.publicClient())
                .orElseThrow(() -> new IllegalArgumentException("unknown confidential client"));
        String rawSecret = newSecret();
        int updated = jdbc.update(
                "UPDATE oauth_client SET client_secret_hash = ? WHERE client_id = ?",
                sha256Hex(rawSecret),
                client.clientId());
        if (updated != 1) {
            throw new IllegalStateException("rotate failed");
        }
        return new IssuedSecret(client.clientId(), rawSecret);
    }

    public boolean verifySecret(OauthClientRecord client, String rawSecret) {
        if (client == null || client.clientSecretHash() == null || rawSecret == null || rawSecret.isBlank()) {
            return false;
        }
        byte[] expected = HexFormat.of().parseHex(client.clientSecretHash());
        byte[] actual = sha256Bytes(rawSecret);
        return MessageDigest.isEqual(expected, actual);
    }

    public record IssuedSecret(String clientId, String clientSecret) {}

    private static RowMapper<OauthClientRecord> mapper() {
        return (row, n) -> new OauthClientRecord(
                row.getString("client_id"),
                row.getString("client_secret_hash"),
                row.getString("client_name"),
                row.getString("redirect_uris"),
                row.getString("grant_types"),
                row.getString("scopes"),
                row.getBoolean("require_pkce"),
                row.getBoolean("public_client"),
                row.getBoolean("active"),
                row.getString("tenant_id"),
                row.getBoolean("first_party"));
    }

    static String joinScopes(List<String> scopes) {
        if (scopes == null) {
            return "";
        }
        return scopes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String raw) {
        return HexFormat.of().formatHex(sha256Bytes(raw));
    }

    private static byte[] sha256Bytes(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 required", ex);
        }
    }
}
