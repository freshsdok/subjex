package com.subjex.platform.app.entity;

import com.subjex.platform.contract.connection.ConnectionVendor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * AttrsJsonbQueries — SHARED-1 helpers for PostgreSQL {@code attrs} jsonb containment filters.
 * <p>
 * GIN {@code jsonb_path_ops} favors {@code @>} containment (equality on JSON object keys).
 * Use only when {@link #sqlContainmentEnabled(ConnectionVendor)} is true.
 * GIN jsonb_path_ops 利于 {@code @>} 包含查询；仅在厂商为 PostgreSQL 时启用 SQL 路径。
 */
public final class AttrsJsonbQueries {

    private AttrsJsonbQueries() {}

    /**
     * True when HybridEntityStore should filter non-promoted fields with {@code attrs @> ?::jsonb} —
     * 非升格字段是否走 {@code attrs @>} SQL（仅 PostgreSQL）。
     */
    public static boolean sqlContainmentEnabled(ConnectionVendor vendor) {
        return vendor == ConnectionVendor.POSTGRESQL;
    }

    /**
     * JSON object {@code {field: value}} for containment bind (matches attrs shape) —
     * 用于包含绑定的 JSON 对象（与 attrs 形态一致）。
     */
    public static String equalityContainmentJson(String fieldName, Object value, ObjectMapper json) {
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(json, "json");
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName blank");
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(fieldName, value);
        try {
            return json.writeValueAsString(doc);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("cannot encode attrs containment JSON", ex);
        }
    }

    /**
     * SQL predicate fragment (parameterized) — SQL 谓词片段（参数化）。
     * <p>
     * {@code attrs @> CAST(? AS jsonb)} — works with jsonb column (V32) and uses GIN jsonb_path_ops.
     */
    public static final String ATTRS_CONTAINS = "attrs @> CAST(? AS jsonb)";
}
