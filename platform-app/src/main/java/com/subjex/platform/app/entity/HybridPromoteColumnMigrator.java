package com.subjex.platform.app.entity;

import com.subjex.entity.declare.EntityRenderer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * HybridPromoteColumnMigrator — HY-2b helpers for promote ADD backfill and demote DROP merge.
 * <p>
 * After {@code ADD COLUMN hp_*}, copies matching attrs JSON values into the new column (null column only).
 * Before {@code DROP COLUMN hp_*}, merges non-null column values back into attrs so demote does not lose data.
 * 升格 ADD 后从 attrs 回填列；降格 DROP 前把列值写回 attrs，避免丢数。
 */
public final class HybridPromoteColumnMigrator {

    private static final Pattern PROMOTED_COLUMN = Pattern.compile("hp_[a-z][a-z0-9_]*");

    /** {@code ALTER TABLE entity_record ADD COLUMN hp_…} — 升格加列。 */
    private static final Pattern ADD_HP = Pattern.compile(
            "(?i)^ALTER\\s+TABLE\\s+entity_record\\s+ADD\\s+COLUMN\\s+(hp_[a-z][a-z0-9_]*)\\b.*$");

    /** {@code ALTER TABLE entity_record DROP COLUMN hp_…} — 降格删列。 */
    private static final Pattern DROP_HP = Pattern.compile(
            "(?i)^ALTER\\s+TABLE\\s+entity_record\\s+DROP\\s+COLUMN\\s+(hp_[a-z][a-z0-9_]*)\\s*$");

    private static final TypeReference<LinkedHashMap<String, Object>> ATTRS_TYPE =
            new TypeReference<LinkedHashMap<String, Object>>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public HybridPromoteColumnMigrator(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** True when SQL is an allowlisted hybrid promote DROP — 是否为白名单升格列 DROP。 */
    public static boolean isHybridPromoteDrop(String sql) {
        return sql != null && DROP_HP.matcher(sql.trim()).matches();
    }

    /** True when SQL is hybrid promote ADD — 是否为升格加列。 */
    public static boolean isHybridPromoteAdd(String sql) {
        return sql != null && ADD_HP.matcher(sql.trim()).matches();
    }

    /** Parse hp_* column from ADD/DROP alter — 从 ADD/DROP 语句解析升格列名。 */
    public static Optional<String> parsePromotedColumn(String sql) {
        if (sql == null) {
            return Optional.empty();
        }
        String body = sql.trim();
        Matcher add = ADD_HP.matcher(body);
        if (add.matches()) {
            return Optional.of(add.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher drop = DROP_HP.matcher(body);
        if (drop.matches()) {
            return Optional.of(drop.group(1).toLowerCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    /**
     * Field snake suffix after {@code hp_<entity>_} — 列名去掉实体前缀后的字段蛇形后缀。
     */
    public static Optional<String> fieldSnakeFromColumn(String entityKey, String column) {
        if (entityKey == null || column == null || !PROMOTED_COLUMN.matcher(column).matches()) {
            return Optional.empty();
        }
        String prefix = "hp_" + entityKey.trim().replace('-', '_') + "_";
        if (!column.startsWith(prefix) || column.length() <= prefix.length()) {
            return Optional.empty();
        }
        return Optional.of(column.substring(prefix.length()));
    }

    /**
     * Copy attrs values into null hp_* cells for one entity (HY-2b) —
     * 把 attrs 中的值写入仍为 null 的升格列（HY-2b）。
     *
     * @return number of rows updated / 更新行数
     */
    public int backfillFromAttrs(String entityKey, String columnName) {
        String key = requireEntityKey(entityKey);
        String column = requirePromotedColumn(columnName);
        String fieldSnake = fieldSnakeFromColumn(key, column)
                .orElseThrow(() -> new IllegalArgumentException("column does not match entityKey: " + column));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tenant_id, record_id, attrs, " + column + " AS hp_val FROM entity_record WHERE entity_key = ?",
                key);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            if (row.get("hp_val") != null) {
                continue;
            }
            Map<String, Object> attrs = readAttrs((String) row.get("attrs"));
            Object value = findAttrValue(attrs, fieldSnake);
            if (value == null) {
                continue;
            }
            jdbc.update(
                    "UPDATE entity_record SET " + column + " = ?, attrs = ?, updated_at = CURRENT_TIMESTAMP"
                            + " WHERE tenant_id = ? AND entity_key = ? AND record_id = ?",
                    jdbcValue(value),
                    writeAttrs(withoutAttrKey(attrs, fieldSnake)),
                    row.get("tenant_id"),
                    key,
                    row.get("record_id"));
            updated++;
        }
        return updated;
    }

    /**
     * Merge non-null hp_* into attrs before DROP (HY-2b demote) —
     * 降格 DROP 前把非空升格列写回 attrs（HY-2b）。
     *
     * @return number of rows updated / 更新行数
     */
    public int mergeIntoAttrs(String entityKey, String columnName) {
        String key = requireEntityKey(entityKey);
        String column = requirePromotedColumn(columnName);
        String fieldSnake = fieldSnakeFromColumn(key, column)
                .orElseThrow(() -> new IllegalArgumentException("column does not match entityKey: " + column));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT tenant_id, record_id, attrs, " + column + " AS hp_val FROM entity_record WHERE entity_key = ?",
                key);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            Object hpVal = row.get("hp_val");
            if (hpVal == null) {
                continue;
            }
            Map<String, Object> attrs = new LinkedHashMap<>(readAttrs((String) row.get("attrs")));
            String attrKey = resolveAttrKey(attrs, fieldSnake);
            attrs.put(attrKey, hpVal);
            jdbc.update(
                    "UPDATE entity_record SET attrs = ?, updated_at = CURRENT_TIMESTAMP"
                            + " WHERE tenant_id = ? AND entity_key = ? AND record_id = ?",
                    writeAttrs(attrs),
                    row.get("tenant_id"),
                    key,
                    row.get("record_id"));
            updated++;
        }
        return updated;
    }

    private static Object findAttrValue(Map<String, Object> attrs, String fieldSnake) {
        if (attrs.containsKey(fieldSnake)) {
            return attrs.get(fieldSnake);
        }
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (EntityRenderer.toSnakeCase(e.getKey()).equals(fieldSnake)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String resolveAttrKey(Map<String, Object> attrs, String fieldSnake) {
        if (attrs.containsKey(fieldSnake)) {
            return fieldSnake;
        }
        for (String k : attrs.keySet()) {
            if (EntityRenderer.toSnakeCase(k).equals(fieldSnake)) {
                return k;
            }
        }
        // Prefer camel-ish: leave snake as key when attrs had no prior key
        // attrs 无旧键时直接用蛇形名写入
        return fieldSnake;
    }

    private static Map<String, Object> withoutAttrKey(Map<String, Object> attrs, String fieldSnake) {
        Map<String, Object> next = new LinkedHashMap<>(attrs);
        next.remove(fieldSnake);
        next.keySet().removeIf(k -> EntityRenderer.toSnakeCase(k).equals(fieldSnake));
        return next;
    }

    private static Object jdbcValue(Object value) {
        if (value instanceof Boolean || value instanceof Number || value instanceof String) {
            return value;
        }
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> readAttrs(String attrsJson) {
        if (attrsJson == null || attrsJson.isBlank()) {
            return Map.of();
        }
        try {
            LinkedHashMap<String, Object> raw = json.readValue(attrsJson, ATTRS_TYPE);
            return raw == null ? Map.of() : raw;
        } catch (JacksonException ex) {
            throw new IllegalStateException("attrs cannot be read as JSON", ex);
        }
    }

    private String writeAttrs(Map<String, Object> attrs) {
        try {
            return json.writeValueAsString(attrs);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("attrs cannot be written as JSON", ex);
        }
    }

    private static String requireEntityKey(String entityKey) {
        if (entityKey == null || entityKey.isBlank()) {
            throw new IllegalArgumentException("entityKey required");
        }
        return entityKey.trim();
    }

    private static String requirePromotedColumn(String column) {
        if (column == null || !PROMOTED_COLUMN.matcher(column).matches()) {
            throw new IllegalArgumentException("invalid promoted column name: " + column);
        }
        return column;
    }
}
