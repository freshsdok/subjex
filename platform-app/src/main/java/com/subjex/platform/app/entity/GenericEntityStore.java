package com.subjex.platform.app.entity;

import com.subjex.entity.declare.EntityField;
import com.subjex.entity.declare.RenderedEntity;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * GenericEntityStore — 通用实体 JDBC 存储：按 {@link RenderedEntity} 元数据拼 SELECT/INSERT/UPDATE/DELETE。
 * <p>
 * Spring-free store class (uses {@link JdbcTemplate} only). Physical-table track ({@code storageMode=table}).
 * Hybrid shared-table track: {@link HybridEntityStore} (ADR 0002 / ES-1).
 * 物理表轨（storageMode=table）。混合共享表轨见 {@link HybridEntityStore}。
 * Save is upsert (update then insert, retry update on race). List orders by primary-key column ASC.
 * When {@link RenderedEntity#tenantScoped()} is true, every SQL path filters/stamps the physical
 * {@code tenant_id} column (VARCHAR) from the caller tenant; client body keys {@code tenantId}/{@code tenant_id}
 * are stripped (header wins). Tables for scoped entities must include {@code tenant_id VARCHAR(64) NOT NULL}.
 * 有 Spring 注解的存储类（只用 {@link JdbcTemplate}）。新实体只需 YAML + Flyway，无专用 Java。
 * 保存为 upsert。列表按主键列升序。{@code tenantScoped} 时按物理列 {@code tenant_id} 隔离；调用方须提供非空租户。
 */
public final class GenericEntityStore {

    /** Physical tenant isolation column — 租户隔离物理列。 */
    public static final String TENANT_COLUMN = "tenant_id";

    private final JdbcTemplate jdbc;

    public GenericEntityStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Upsert one record (non-scoped or tenant ignored) — 按 camelCase 字段名 upsert 一行（非隔离或忽略租户）。
     */
    public void save(RenderedEntity entity, Map<String, Object> values) {
        save(entity, values, null);
    }

    /**
     * Upsert one record; when scoped, {@code tenantId} is required and stamped —
     * 按 camelCase 字段名 upsert；隔离时 {@code tenantId} 必填并盖章。
     */
    public void save(RenderedEntity entity, Map<String, Object> values, String tenantId) {
        Objects.requireNonNull(entity, "entity");
        String effectiveTenant = effectiveTenant(entity, tenantId);
        Map<String, Object> accepted = validateWrite(entity, scrubClientTenantKeys(values), effectiveTenant);
        int updated = update(entity, accepted, effectiveTenant);
        if (updated == 0) {
            try {
                insert(entity, accepted, effectiveTenant);
            } catch (DuplicateKeyException raced) {
                update(entity, accepted, effectiveTenant);
            }
        }
    }

    /**
     * Find by primary-key string value — 按主键字符串取值查找。
     */
    public Optional<Map<String, Object>> findById(RenderedEntity entity, String id) {
        return findById(entity, id, null);
    }

    /**
     * Find by primary key; when scoped, also match {@code tenant_id} —
     * 按主键查找；隔离时同时匹配 {@code tenant_id}。
     */
    public Optional<Map<String, Object>> findById(RenderedEntity entity, String id, String tenantId) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(id, "id");
        String effectiveTenant = effectiveTenant(entity, tenantId);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(columnList(entity))
                .append(" FROM ")
                .append(entity.tableName())
                .append(" WHERE ")
                .append(entity.primaryKey().columnName())
                .append(" = ?");
        List<Object> args = new ArrayList<>();
        args.add(id);
        appendTenantPredicate(sql, args, entity, effectiveTenant);
        return jdbc.query(sql.toString(), rowMapper(entity), args.toArray()).stream().findFirst();
    }

    /**
     * List up to {@code limit} rows ordered by PK ASC — 按主键升序列出最多 limit 行。
     */
    public List<Map<String, Object>> list(RenderedEntity entity, int limit) {
        return list(entity, limit, null, true, null, null, null);
    }

    /**
     * List with optional sort/filter (tenant ignored when not scoped) —
     * 可选排序/筛选列表（非隔离时忽略租户）。
     */
    public List<Map<String, Object>> list(
            RenderedEntity entity,
            int limit,
            String sortField,
            boolean ascending,
            String filterField,
            String filterValue) {
        return list(entity, limit, sortField, ascending, filterField, filterValue, null);
    }

    /**
     * List with optional sort/filter; field names must be declared (fail-closed).
     * 可选排序/筛选列表；字段名必须声明（失败关闭）。值仅走参数绑定。
     * When scoped, rows are limited to {@code tenantId}.
     * 隔离时只返回该租户行。
     *
     * @param sortField declared field name, or {@code null} for primary key
     * @param ascending sort direction (ignored only when using default PK ASC via null sort)
     * @param filterField declared field name, or {@code null} for no filter
     * @param filterValue raw filter string (coerced by field kind); required with filterField
     * @param tenantId required non-blank when {@code entity.tenantScoped()}; otherwise ignored
     */
    public List<Map<String, Object>> list(
            RenderedEntity entity,
            int limit,
            String sortField,
            boolean ascending,
            String filterField,
            String filterValue,
            String tenantId) {
        Objects.requireNonNull(entity, "entity");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        String effectiveTenant = effectiveTenant(entity, tenantId);
        boolean hasFilterField = filterField != null && !filterField.isBlank();
        boolean hasFilterValue = filterValue != null;
        if (hasFilterField != hasFilterValue) {
            throw new IllegalArgumentException("filterField and filterValue must be provided together");
        }
        EntityField orderField = resolveDeclaredField(entity, sortField, "sort");
        if (orderField == null) {
            orderField = entity.primaryKey();
        }
        String orderDir = ascending ? "ASC" : "DESC";
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(columnList(entity))
                .append(" FROM ")
                .append(entity.tableName());
        List<Object> args = new ArrayList<>();
        boolean whereStarted = false;
        if (entity.tenantScoped()) {
            sql.append(" WHERE ").append(TENANT_COLUMN).append(" = ?");
            args.add(effectiveTenant);
            whereStarted = true;
        }
        if (hasFilterField) {
            EntityField whereField = resolveDeclaredField(entity, filterField, "filterField");
            if (whereField == null) {
                throw new IllegalArgumentException("unknown filterField: " + filterField);
            }
            Object bound = coerceFilterValue(whereField, filterValue);
            sql.append(whereStarted ? " AND " : " WHERE ")
                    .append(whereField.columnName())
                    .append(" = ?");
            args.add(bound);
        }
        sql.append(" ORDER BY ").append(orderField.columnName()).append(" ").append(orderDir).append(" LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), rowMapper(entity), args.toArray());
    }

    private static EntityField resolveDeclaredField(RenderedEntity entity, String fieldName, String label) {
        if (fieldName == null || fieldName.isBlank()) {
            return null;
        }
        for (EntityField field : entity.fields()) {
            if (field.name().equals(fieldName)) {
                return field;
            }
        }
        throw new IllegalArgumentException("unknown " + label + ": " + fieldName);
    }

    private static Object coerceFilterValue(EntityField field, String raw) {
        return switch (field.kind()) {
            case BOOLEAN -> {
                String trimmed = raw.trim();
                if ("true".equalsIgnoreCase(trimmed)) {
                    yield Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(trimmed)) {
                    yield Boolean.FALSE;
                }
                throw new IllegalArgumentException("filterValue must be boolean for field: " + field.name());
            }
            case INTEGER -> {
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) {
                    throw new IllegalArgumentException("filterValue must be integer for field: " + field.name());
                }
                try {
                    yield Integer.valueOf(trimmed);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("filterValue must be integer for field: " + field.name());
                }
            }
            case TEXT, ENUM, DATE, SUBJECT_REF, ORGANIZATION_REF, ENTITY_REF -> raw;
        };
    }

    /**
     * Delete by primary key; returns whether a row was removed — 按主键删除；返回是否删到行。
     */
    public boolean deleteById(RenderedEntity entity, String id) {
        return deleteById(entity, id, null);
    }

    /**
     * Delete by primary key; when scoped, also match {@code tenant_id} —
     * 按主键删除；隔离时同时匹配 {@code tenant_id}。
     */
    public boolean deleteById(RenderedEntity entity, String id, String tenantId) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(id, "id");
        String effectiveTenant = effectiveTenant(entity, tenantId);
        StringBuilder sql = new StringBuilder("DELETE FROM ")
                .append(entity.tableName())
                .append(" WHERE ")
                .append(entity.primaryKey().columnName())
                .append(" = ?");
        List<Object> args = new ArrayList<>();
        args.add(id);
        appendTenantPredicate(sql, args, entity, effectiveTenant);
        return jdbc.update(sql.toString(), args.toArray()) > 0;
    }

    /**
     * Validate body against fields; reject unknown keys — 按字段校验写入体；拒绝未知键。
     * When scoped and a YAML field maps to {@code tenant_id}, stamps {@code effectiveTenant}.
     */
    Map<String, Object> validateWrite(RenderedEntity entity, Map<String, Object> values) {
        return validateWrite(entity, values, null);
    }

    Map<String, Object> validateWrite(RenderedEntity entity, Map<String, Object> values, String effectiveTenant) {
        if (values == null) {
            throw new IllegalArgumentException("body is required");
        }
        Map<String, Object> scrubbed = scrubClientTenantKeys(values);
        if (entity.tenantScoped() && effectiveTenant != null) {
            for (EntityField field : entity.fields()) {
                if (TENANT_COLUMN.equals(field.columnName())) {
                    scrubbed.put(field.name(), effectiveTenant);
                }
            }
        }
        Set<String> known = entity.fields().stream().map(EntityField::name).collect(Collectors.toCollection(LinkedHashSet::new));
        for (String key : scrubbed.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException("unknown field: " + key);
            }
        }
        Map<String, Object> accepted = new LinkedHashMap<>();
        for (EntityField field : entity.fields()) {
            Object raw = scrubbed.get(field.name());
            boolean present = scrubbed.containsKey(field.name()) && raw != null;
            if (field.required() && !present) {
                throw new IllegalArgumentException("field required: " + field.name());
            }
            if (!present) {
                accepted.put(field.name(), null);
                continue;
            }
            accepted.put(field.name(), coerce(field, raw));
        }
        return accepted;
    }

    private static Object coerce(EntityField field, Object raw) {
        return switch (field.kind()) {
            case INTEGER -> coerceInteger(field.name(), raw);
            case BOOLEAN -> coerceBoolean(field.name(), raw);
            case DATE -> coerceDate(field.name(), raw);
            case ENUM -> coerceEnum(field, raw);
            case TEXT, SUBJECT_REF, ORGANIZATION_REF, ENTITY_REF -> coerceText(field, raw);
        };
    }

    private static Integer coerceInteger(String name, Object raw) {
        if (raw instanceof Integer i) {
            return i;
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("field must be integer: " + name);
            }
            try {
                return Integer.valueOf(trimmed);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("field must be integer: " + name);
            }
        }
        throw new IllegalArgumentException("field must be integer: " + name);
    }

    private static String coerceText(EntityField field, Object raw) {
        if (!(raw instanceof String s)) {
            throw new IllegalArgumentException("field must be text: " + field.name());
        }
        if (field.required() && s.isBlank()) {
            throw new IllegalArgumentException("field required: " + field.name());
        }
        Integer max = field.maxLength();
        if (max != null && s.length() > max) {
            throw new IllegalArgumentException("field too long: " + field.name());
        }
        return s;
    }

    private static Boolean coerceBoolean(String name, Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return Boolean.FALSE;
            }
        }
        throw new IllegalArgumentException("field must be boolean: " + name);
    }

    private static String coerceDate(String name, Object raw) {
        if (!(raw instanceof String s)) {
            throw new IllegalArgumentException("field must be date (yyyy-MM-dd): " + name);
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("field must be date (yyyy-MM-dd): " + name);
        }
        try {
            LocalDate.parse(trimmed);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("field must be date (yyyy-MM-dd): " + name);
        }
        return trimmed;
    }

    private static String coerceEnum(EntityField field, Object raw) {
        String value = coerceText(field, raw);
        if (!field.enumValues().contains(value)) {
            throw new IllegalArgumentException("field must be one of enumValues: " + field.name());
        }
        return value;
    }

    private int update(RenderedEntity entity, Map<String, Object> accepted, String effectiveTenant) {
        List<EntityField> nonPk = nonPrimaryFields(entity);
        if (nonPk.isEmpty()) {
            return findById(entity, String.valueOf(accepted.get(entity.primaryKey().name())), effectiveTenant)
                            .isPresent()
                    ? 1
                    : 0;
        }
        boolean stampExtraTenant = entity.tenantScoped() && !hasTenantColumnField(entity);
        String setClause = nonPk.stream()
                .map(f -> f.columnName() + " = ?")
                .collect(Collectors.joining(", "));
        if (stampExtraTenant) {
            setClause = setClause + ", " + TENANT_COLUMN + " = ?";
        }
        List<Object> args = new ArrayList<>();
        for (EntityField field : nonPk) {
            args.add(accepted.get(field.name()));
        }
        if (stampExtraTenant) {
            args.add(effectiveTenant);
        }
        args.add(accepted.get(entity.primaryKey().name()));
        StringBuilder sql = new StringBuilder("UPDATE ")
                .append(entity.tableName())
                .append(" SET ")
                .append(setClause)
                .append(" WHERE ")
                .append(entity.primaryKey().columnName())
                .append(" = ?");
        appendTenantPredicate(sql, args, entity, effectiveTenant);
        return jdbc.update(sql.toString(), args.toArray());
    }

    private void insert(RenderedEntity entity, Map<String, Object> accepted, String effectiveTenant) {
        boolean stampExtraTenant = entity.tenantScoped() && !hasTenantColumnField(entity);
        String cols = columnList(entity);
        String placeholders = entity.fields().stream().map(f -> "?").collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>();
        for (EntityField field : entity.fields()) {
            args.add(accepted.get(field.name()));
        }
        if (stampExtraTenant) {
            cols = cols + ", " + TENANT_COLUMN;
            placeholders = placeholders + ", ?";
            args.add(effectiveTenant);
        }
        jdbc.update(
                "INSERT INTO " + entity.tableName() + " (" + cols + ") VALUES (" + placeholders + ")",
                args.toArray());
    }

    private static List<EntityField> nonPrimaryFields(RenderedEntity entity) {
        EntityField pk = entity.primaryKey();
        return entity.fields().stream().filter(f -> !f.name().equals(pk.name())).toList();
    }

    private static String columnList(RenderedEntity entity) {
        return entity.fields().stream().map(EntityField::columnName).collect(Collectors.joining(", "));
    }

    private static boolean hasTenantColumnField(RenderedEntity entity) {
        return entity.fields().stream().anyMatch(f -> TENANT_COLUMN.equals(f.columnName()));
    }

    /**
     * When scoped, require non-blank tenant; otherwise return null (caller ignores).
     * 隔离时要求非空租户；否则返回 null（调用方忽略）。
     */
    private static String effectiveTenant(RenderedEntity entity, String tenantId) {
        if (!entity.tenantScoped()) {
            return null;
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required for tenantScoped entity");
        }
        return tenantId.trim();
    }

    private static void appendTenantPredicate(
            StringBuilder sql, List<Object> args, RenderedEntity entity, String effectiveTenant) {
        if (entity.tenantScoped()) {
            sql.append(" AND ").append(TENANT_COLUMN).append(" = ?");
            args.add(effectiveTenant);
        }
    }

    private static Map<String, Object> scrubClientTenantKeys(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.remove("tenantId");
        copy.remove("tenant_id");
        return copy;
    }

    private static RowMapper<Map<String, Object>> rowMapper(RenderedEntity entity) {
        return (row, rowNum) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            for (EntityField field : entity.fields()) {
                String col = field.columnName();
                switch (field.kind()) {
                    case INTEGER -> {
                        int value = row.getInt(col);
                        out.put(field.name(), row.wasNull() ? null : value);
                    }
                    case BOOLEAN -> {
                        boolean value = row.getBoolean(col);
                        out.put(field.name(), row.wasNull() ? null : value);
                    }
                    case TEXT, ENUM, DATE, SUBJECT_REF, ORGANIZATION_REF, ENTITY_REF ->
                            out.put(field.name(), row.getString(col));
                }
            }
            return out;
        };
    }
}
