package com.subjex.platform.app.entity;

import com.subjex.entity.declare.EntityField;
import com.subjex.entity.declare.EntityFieldKind;
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
 * Spring-free store class (uses {@link JdbcTemplate} only). New entity → YAML + Flyway, no bespoke Java.
 * Save is upsert (update then insert, retry update on race). List orders by primary-key column ASC.
 * 无 Spring 注解的存储类（只用 {@link JdbcTemplate}）。新实体只需 YAML + Flyway，无专用 Java。
 * 保存为 upsert。列表按主键列升序。
 */
public final class GenericEntityStore {

    private final JdbcTemplate jdbc;

    public GenericEntityStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * Upsert one record; values keyed by camelCase field names — 按 camelCase 字段名 upsert 一行。
     */
    public void save(RenderedEntity entity, Map<String, Object> values) {
        Objects.requireNonNull(entity, "entity");
        Map<String, Object> accepted = validateWrite(entity, values);
        int updated = update(entity, accepted);
        if (updated == 0) {
            try {
                insert(entity, accepted);
            } catch (DuplicateKeyException raced) {
                update(entity, accepted);
            }
        }
    }

    /**
     * Find by primary-key string value — 按主键字符串取值查找。
     */
    public Optional<Map<String, Object>> findById(RenderedEntity entity, String id) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(id, "id");
        String sql = "SELECT " + columnList(entity) + " FROM " + entity.tableName()
                + " WHERE " + entity.primaryKey().columnName() + " = ?";
        return jdbc.query(sql, rowMapper(entity), id).stream().findFirst();
    }

    /**
     * List up to {@code limit} rows ordered by PK ASC — 按主键升序列出最多 limit 行。
     */
    public List<Map<String, Object>> list(RenderedEntity entity, int limit) {
        return list(entity, limit, null, true, null, null);
    }

    /**
     * List with optional sort/filter; field names must be declared (fail-closed).
     * 可选排序/筛选列表；字段名必须声明（失败关闭）。值仅走参数绑定。
     *
     * @param sortField declared field name, or {@code null} for primary key
     * @param ascending sort direction (ignored only when using default PK ASC via null sort)
     * @param filterField declared field name, or {@code null} for no filter
     * @param filterValue raw filter string (coerced by field kind); required with filterField
     */
    public List<Map<String, Object>> list(
            RenderedEntity entity,
            int limit,
            String sortField,
            boolean ascending,
            String filterField,
            String filterValue) {
        Objects.requireNonNull(entity, "entity");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
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
        if (hasFilterField) {
            EntityField whereField = resolveDeclaredField(entity, filterField, "filterField");
            if (whereField == null) {
                throw new IllegalArgumentException("unknown filterField: " + filterField);
            }
            Object bound = coerceFilterValue(whereField, filterValue);
            sql.append(" WHERE ").append(whereField.columnName()).append(" = ?");
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
            case TEXT, ENUM, DATE, USER_REF, ORG_REF, ENTITY_REF -> raw;
        };
    }

    /**
     * Delete by primary key; returns whether a row was removed — 按主键删除；返回是否删到行。
     */
    public boolean deleteById(RenderedEntity entity, String id) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(id, "id");
        int deleted = jdbc.update(
                "DELETE FROM " + entity.tableName() + " WHERE " + entity.primaryKey().columnName() + " = ?",
                id);
        return deleted > 0;
    }

    /**
     * Validate body against fields; reject unknown keys — 按字段校验写入体；拒绝未知键。
     */
    Map<String, Object> validateWrite(RenderedEntity entity, Map<String, Object> values) {
        if (values == null) {
            throw new IllegalArgumentException("body is required");
        }
        Set<String> known = entity.fields().stream().map(EntityField::name).collect(Collectors.toCollection(LinkedHashSet::new));
        for (String key : values.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException("unknown field: " + key);
            }
        }
        Map<String, Object> accepted = new LinkedHashMap<>();
        for (EntityField field : entity.fields()) {
            Object raw = values.get(field.name());
            boolean present = values.containsKey(field.name()) && raw != null;
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
            case TEXT, USER_REF, ORG_REF, ENTITY_REF -> coerceText(field, raw);
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

    private int update(RenderedEntity entity, Map<String, Object> accepted) {
        List<EntityField> nonPk = nonPrimaryFields(entity);
        if (nonPk.isEmpty()) {
            // Only PK column — update is a no-op existence check via returning 0 when missing.
            // 仅有主键列时：用 find 判断存在；此处返回 0 让 insert 路径负责新建。
            return findById(entity, String.valueOf(accepted.get(entity.primaryKey().name()))).isPresent() ? 1 : 0;
        }
        String setClause = nonPk.stream()
                .map(f -> f.columnName() + " = ?")
                .collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>();
        for (EntityField field : nonPk) {
            args.add(accepted.get(field.name()));
        }
        args.add(accepted.get(entity.primaryKey().name()));
        return jdbc.update(
                "UPDATE " + entity.tableName() + " SET " + setClause
                        + " WHERE " + entity.primaryKey().columnName() + " = ?",
                args.toArray());
    }

    private void insert(RenderedEntity entity, Map<String, Object> accepted) {
        String cols = columnList(entity);
        String placeholders = entity.fields().stream().map(f -> "?").collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>();
        for (EntityField field : entity.fields()) {
            args.add(accepted.get(field.name()));
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
                    case TEXT, ENUM, DATE, USER_REF, ORG_REF, ENTITY_REF ->
                            out.put(field.name(), row.getString(col));
                }
            }
            return out;
        };
    }
}
