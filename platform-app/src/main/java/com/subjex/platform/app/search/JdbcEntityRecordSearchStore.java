package com.subjex.platform.app.search;

import com.subjex.platform.app.entity.AttrsJsonbQueries;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * JdbcEntityRecordSearchStore — portable substring + optional PG jsonb {@code @>} equality.
 * H2/MySQL: {@code LOWER(attrs) LIKE}; PostgreSQL jsonb: {@code LOWER(CAST(attrs AS text)) LIKE}
 * and optional GIN containment for field=value.
 * 可移植子串；PG 另支持字段等值 GIN 包含。写路径不经此存储。
 */
public final class JdbcEntityRecordSearchStore implements EntityRecordSearchStore {

    private static final int ATTRS_PREVIEW_CHARS = 240;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final boolean attrsJsonb;

    public JdbcEntityRecordSearchStore(JdbcTemplate jdbc, ObjectMapper json, boolean attrsJsonb) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.attrsJsonb = attrsJsonb;
    }

    @Override
    public List<EntityRecordSearchHit> search(
            String tenantId, String entityKey, String q, String field, String value, int limit) {
        String tid = Objects.requireNonNull(tenantId, "tenantId");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT);
        }
        String ek = blankToNull(entityKey);
        String query = q == null ? "" : q.trim();
        String f = blankToNull(field);
        String v = value;
        boolean hasField = f != null;
        boolean hasValue = v != null;
        if (hasField != hasValue) {
            throw new IllegalArgumentException("field and value must be provided together");
        }
        if (query.isEmpty() && !hasField) {
            throw new IllegalArgumentException("q or field+value required");
        }

        String attrsExpr = attrsJsonb ? "CAST(attrs AS text)" : "attrs";
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                """
                SELECT tenant_id, entity_key, record_id, record_state, attrs, updated_at
                FROM entity_record
                WHERE tenant_id = ?
                """);
        args.add(tid);
        if (ek != null) {
            sql.append(" AND entity_key = ?");
            args.add(ek);
        }
        if (!query.isEmpty()) {
            String like = "%" + escapeLike(query.toLowerCase()) + "%";
            sql.append(" AND (LOWER(record_id) LIKE ? ESCAPE '\\' OR LOWER(")
                    .append(attrsExpr)
                    .append(") LIKE ? ESCAPE '\\')");
            args.add(like);
            args.add(like);
        }
        if (hasField) {
            if (attrsJsonb) {
                String containment = AttrsJsonbQueries.equalityContainmentJson(f, coerceLoose(v), json);
                sql.append(" AND ").append(AttrsJsonbQueries.ATTRS_CONTAINS);
                args.add(containment);
            } else {
                // Portable: attrs JSON text contains "field":value fragment (best-effort).
                // 可移植：attrs 文本包含 "field":value 片段（尽力）。
                String like = "%\"" + escapeLike(f) + "\"%" + escapeLike(v) + "%";
                sql.append(" AND LOWER(").append(attrsExpr).append(") LIKE ? ESCAPE '\\'");
                args.add(like.toLowerCase());
            }
        }
        sql.append(" ORDER BY updated_at DESC, entity_key ASC, record_id ASC LIMIT ?");
        args.add(limit);

        return jdbc.query(sql.toString(), hitMapper(), args.toArray());
    }

    private RowMapper<EntityRecordSearchHit> hitMapper() {
        return (row, n) -> {
            String attrs = row.getString("attrs");
            String preview = attrs == null ? "" : attrs;
            if (preview.length() > ATTRS_PREVIEW_CHARS) {
                preview = preview.substring(0, ATTRS_PREVIEW_CHARS) + "…";
            }
            Object updated = row.getObject("updated_at");
            return new EntityRecordSearchHit(
                    row.getString("tenant_id"),
                    row.getString("entity_key"),
                    row.getString("record_id"),
                    row.getString("record_state"),
                    preview,
                    updated == null ? null : updated.toString());
        };
    }

    private static Object coerceLoose(String raw) {
        String t = raw.trim();
        if ("true".equalsIgnoreCase(t)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(t)) {
            return Boolean.FALSE;
        }
        try {
            if (!t.contains(".") && t.length() < 18) {
                return Long.parseLong(t);
            }
        } catch (NumberFormatException ignored) {
            // keep string
        }
        return t;
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
