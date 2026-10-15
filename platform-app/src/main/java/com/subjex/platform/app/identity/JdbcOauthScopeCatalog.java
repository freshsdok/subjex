package com.subjex.platform.app.identity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcOauthScopeCatalog — ID-3 scope catalog (openid/profile + API scopes).
 * 范围目录（openid/profile 与 API 范围）。
 */
public final class JdbcOauthScopeCatalog {

    private final JdbcTemplate jdbc;

    public JdbcOauthScopeCatalog(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public List<OauthScopeRecord> listAll() {
        return jdbc.query(
                """
                SELECT scope_name, description_en, description_zh, permission_name
                  FROM oauth_scope
                 ORDER BY scope_name
                """,
                mapper());
    }

    public Optional<OauthScopeRecord> find(String scopeName) {
        if (scopeName == null || scopeName.isBlank()) {
            return Optional.empty();
        }
        List<OauthScopeRecord> rows = jdbc.query(
                """
                SELECT scope_name, description_en, description_zh, permission_name
                  FROM oauth_scope
                 WHERE scope_name = ?
                """,
                mapper(),
                scopeName.trim());
        return rows.stream().findFirst();
    }

    /** Intersect requested with catalog names — 与目录求交。 */
    public Set<String> knownNames() {
        return listAll().stream().map(OauthScopeRecord::scopeName).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static RowMapper<OauthScopeRecord> mapper() {
        return (row, n) -> new OauthScopeRecord(
                row.getString("scope_name"),
                row.getString("description_en"),
                row.getString("description_zh"),
                row.getString("permission_name"));
    }
}
