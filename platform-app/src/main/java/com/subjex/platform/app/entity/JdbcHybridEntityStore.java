package com.subjex.platform.app.entity;

import com.subjex.entity.declare.EntityField;
import com.subjex.entity.declare.EntityFieldKind;
import com.subjex.entity.declare.EntityStorageMode;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.jdbc.PlatformTables;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * JdbcHybridEntityStore — JDBC 实现：读写共享表 {@code entity_record}（ES-1 / ADR 0002 / HY-2）。
 * <p>
 * Core columns: tenant_id, entity_key, record_id, record_state, attrs (JSON text), created_at, updated_at.
 * HY-2: fields with {@code promote: true} also use typed {@code hp_<entity>_<field>} columns; attrs omit those
 * keys on write; reads merge column values over attrs.
 * DOC-1: also accepts storageMode=document (attrs-only; no promote columns).
 * SHARED-1: on PostgreSQL, non-promoted equality filters use {@code attrs @> CAST(? AS jsonb)} (GIN).
 * Non-scoped entities stamp tenant_id as empty string (platform sentinel). Rejects storageMode=table.
 * 核心列见上；HY-2 升格字段读写物理列；DOC-1 亦受理 document（仅 attrs）；非隔离实体 tenant_id 盖空串；拒绝 table 轨。
 */
public final class JdbcHybridEntityStore implements HybridEntityStore {

    /** Shared hybrid table — 共享混合表。 */
    public static final String TABLE = "entity_record";

    /** Default lifecycle state — 默认生命周期状态。 */
    public static final String STATE_ACTIVE = "ACTIVE";

    /** Tenant sentinel for non-scoped entities — 非隔离实体的租户占位。 */
    public static final String PLATFORM_TENANT = "";

    /** Fail-closed guard for dynamic promoted column identifiers — 升格列标识符失败关闭校验。 */
    private static final Pattern PROMOTED_COLUMN = Pattern.compile("hp_[a-z][a-z0-9_]*");

    private static final TypeReference<LinkedHashMap<String, Object>> ATTRS_TYPE =
            new TypeReference<LinkedHashMap<String, Object>>() {};

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper json;
    private final GenericEntityStore validator;
    /** When true, non-promoted filters use PG jsonb containment (SHARED-1) — 为 true 时非升格过滤走 PG jsonb 包含。 */
    private final boolean attrsJsonbSqlFilter;

    /** Tests / H2: jsonb SQL filter off — 测试/H2：关闭 jsonb SQL 过滤。 */
    public JdbcHybridEntityStore(JdbcTemplate jdbc, Clock clock, ObjectMapper json) {
        this(jdbc, clock, json, false);
    }

    public JdbcHybridEntityStore(JdbcTemplate jdbc, Clock clock, ObjectMapper json, boolean attrsJsonbSqlFilter) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
        this.validator = new GenericEntityStore(jdbc);
        this.attrsJsonbSqlFilter = attrsJsonbSqlFilter;
    }

    @Override
    public void save(RenderedEntity entity, Map<String, Object> values, String tenantId) {
        Objects.requireNonNull(entity, "entity");
        requireHybrid(entity);
        String effectiveTenant = effectiveTenant(entity, tenantId);
        Map<String, Object> accepted = validator.validateWrite(entity, values, effectiveTenant);
        Object pkRaw = accepted.get(entity.primaryKey().name());
        if (pkRaw == null || pkRaw.toString().isBlank()) {
            throw new IllegalArgumentException("primary key required: " + entity.primaryKey().name());
        }
        String recordId = pkRaw.toString();
        List<EntityField> promoted = promotedFields(entity);
        Map<String, Object> attrsMap = attrsWithoutPromoted(accepted, promoted);
        String attrsJson = writeAttrs(attrsMap);
        AttrsPayloadLimits.requireWithinLimits(attrsMap, attrsJson);
        Timestamp now = PlatformTables.timestamp(clock.instant());
        List<Object> promotedValues = promotedJdbcValues(promoted, accepted);

        String setPromoted = promotedSetClause(promoted, entity.entityKey());
        String updateSql =
                "UPDATE entity_record SET record_state = ?, attrs = ?, updated_at = ?"
                        + setPromoted
                        + " WHERE tenant_id = ? AND entity_key = ? AND record_id = ?";
        List<Object> updateArgs = new ArrayList<>();
        updateArgs.add(STATE_ACTIVE);
        updateArgs.add(attrsJson);
        updateArgs.add(now);
        updateArgs.addAll(promotedValues);
        updateArgs.add(effectiveTenant);
        updateArgs.add(entity.entityKey());
        updateArgs.add(recordId);
        int updated = jdbc.update(updateSql, updateArgs.toArray());
        if (updated == 0) {
            try {
                String insertCols = "tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at";
                String insertQs = "?, ?, ?, ?, ?, ?, ?";
                List<Object> insertArgs = new ArrayList<>();
                insertArgs.add(effectiveTenant);
                insertArgs.add(entity.entityKey());
                insertArgs.add(recordId);
                insertArgs.add(STATE_ACTIVE);
                insertArgs.add(attrsJson);
                insertArgs.add(now);
                insertArgs.add(now);
                for (int i = 0; i < promoted.size(); i++) {
                    insertCols += ", " + requirePromotedColumn(promoted.get(i).hybridPromotedColumnName(entity.entityKey()));
                    insertQs += ", ?";
                    insertArgs.add(promotedValues.get(i));
                }
                jdbc.update(
                        "INSERT INTO entity_record (" + insertCols + ") VALUES (" + insertQs + ")",
                        insertArgs.toArray());
            } catch (DuplicateKeyException raced) {
                jdbc.update(updateSql, updateArgs.toArray());
            }
        }
    }

    @Override
    public Optional<Map<String, Object>> findById(RenderedEntity entity, String id, String tenantId) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(id, "id");
        requireHybrid(entity);
        String effectiveTenant = effectiveTenant(entity, tenantId);
        List<EntityField> promoted = promotedFields(entity);
        String select = selectClause(promoted, entity.entityKey());
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT "
                        + select
                        + " FROM entity_record WHERE tenant_id = ? AND entity_key = ? AND record_id = ?",
                rowMapper(entity, promoted),
                effectiveTenant,
                entity.entityKey(),
                id);
        return rows.stream().findFirst();
    }

    /** Cap for non-PK hybrid filter scan (ES-3 interim) — 非主键过滤扫描上限（ES-3 过渡）。 */
    static final int FILTER_SCAN_CAP = 2000;

    @Override
    public List<Map<String, Object>> list(
            RenderedEntity entity,
            int limit,
            String tenantId,
            boolean ascending,
            String filterField,
            String filterValue) {
        Objects.requireNonNull(entity, "entity");
        requireHybrid(entity);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        boolean hasFilterField = filterField != null && !filterField.isBlank();
        boolean hasFilterValue = filterValue != null;
        if (hasFilterField != hasFilterValue) {
            throw new IllegalArgumentException("filterField and filterValue must be provided together");
        }
        String effectiveTenant = effectiveTenant(entity, tenantId);
        String orderDir = ascending ? "ASC" : "DESC";
        List<EntityField> promoted = promotedFields(entity);
        String select = selectClause(promoted, entity.entityKey());
        RowMapper<Map<String, Object>> mapper = rowMapper(entity, promoted);
        if (!hasFilterField) {
            return jdbc.query(
                    "SELECT "
                            + select
                            + " FROM entity_record WHERE tenant_id = ? AND entity_key = ?"
                            + " ORDER BY record_id "
                            + orderDir
                            + " LIMIT ?",
                    mapper,
                    effectiveTenant,
                    entity.entityKey(),
                    limit);
        }
        String fieldName = filterField.trim();
        boolean pkFilter = entity.primaryKey().name().equals(fieldName);
        if (pkFilter) {
            return jdbc.query(
                    "SELECT "
                            + select
                            + " FROM entity_record WHERE tenant_id = ? AND entity_key = ? AND record_id = ?"
                            + " ORDER BY record_id "
                            + orderDir
                            + " LIMIT ?",
                    mapper,
                    effectiveTenant,
                    entity.entityKey(),
                    filterValue,
                    limit);
        }
        EntityField filterDecl = resolveDeclaredField(entity, fieldName);
        if (filterDecl.promote()) {
            String col = requirePromotedColumn(filterDecl.hybridPromotedColumnName(entity.entityKey()));
            return jdbc.query(
                    "SELECT "
                            + select
                            + " FROM entity_record WHERE tenant_id = ? AND entity_key = ? AND "
                            + col
                            + " = ?"
                            + " ORDER BY record_id "
                            + orderDir
                            + " LIMIT ?",
                    mapper,
                    effectiveTenant,
                    entity.entityKey(),
                    coerceFilterValue(filterDecl, filterValue),
                    limit);
        }
        Object coerced = coerceFilterValue(filterDecl, filterValue);
        if (attrsJsonbSqlFilter) {
            // SHARED-1: GIN jsonb_path_ops containment — SHARED-1：GIN 包含查询
            String containment = AttrsJsonbQueries.equalityContainmentJson(fieldName, coerced, json);
            return jdbc.query(
                    "SELECT "
                            + select
                            + " FROM entity_record WHERE tenant_id = ? AND entity_key = ? AND "
                            + AttrsJsonbQueries.ATTRS_CONTAINS
                            + " ORDER BY record_id "
                            + orderDir
                            + " LIMIT ?",
                    mapper,
                    effectiveTenant,
                    entity.entityKey(),
                    containment,
                    limit);
        }
        int scan = Math.min(Math.max(limit * 20, limit), FILTER_SCAN_CAP);
        List<Map<String, Object>> scanned = jdbc.query(
                "SELECT "
                        + select
                        + " FROM entity_record WHERE tenant_id = ? AND entity_key = ?"
                        + " ORDER BY record_id "
                        + orderDir
                        + " LIMIT ?",
                mapper,
                effectiveTenant,
                entity.entityKey(),
                scan);
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> row : scanned) {
            if (fieldEquals(row.get(fieldName), filterValue)) {
                matched.add(row);
                if (matched.size() >= limit) {
                    break;
                }
            }
        }
        return matched;
    }

    private static EntityField resolveDeclaredField(RenderedEntity entity, String fieldName) {
        for (EntityField field : entity.fields()) {
            if (field.name().equals(fieldName)) {
                return field;
            }
        }
        throw new IllegalArgumentException("unknown filterField: " + fieldName);
    }

    private static Object coerceFilterValue(EntityField field, String rawFilter) {
        return switch (field.kind()) {
            case BOOLEAN -> {
                if ("true".equalsIgnoreCase(rawFilter.trim())) {
                    yield Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(rawFilter.trim())) {
                    yield Boolean.FALSE;
                }
                throw new IllegalArgumentException("boolean filterValue must be true or false");
            }
            case INTEGER -> {
                try {
                    yield Integer.parseInt(rawFilter.trim());
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("integer filterValue required", ex);
                }
            }
            default -> rawFilter;
        };
    }

    private static boolean fieldEquals(Object actual, String rawFilter) {
        if (actual == null) {
            return false;
        }
        if (actual instanceof Boolean b) {
            if ("true".equalsIgnoreCase(rawFilter.trim())) {
                return b;
            }
            if ("false".equalsIgnoreCase(rawFilter.trim())) {
                return !b;
            }
            return false;
        }
        if (actual instanceof Number n) {
            try {
                return n.intValue() == Integer.parseInt(rawFilter.trim());
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return Objects.toString(actual, "").equals(rawFilter);
    }

    @Override
    public boolean deleteById(RenderedEntity entity, String id, String tenantId) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(id, "id");
        requireHybrid(entity);
        String effectiveTenant = effectiveTenant(entity, tenantId);
        return jdbc.update(
                        """
                        DELETE FROM entity_record
                         WHERE tenant_id = ? AND entity_key = ? AND record_id = ?
                        """,
                        effectiveTenant,
                        entity.entityKey(),
                        id)
                > 0;
    }

    private static List<EntityField> promotedFields(RenderedEntity entity) {
        List<EntityField> out = new ArrayList<>();
        for (EntityField field : entity.fields()) {
            if (field.promote()) {
                out.add(field);
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> attrsWithoutPromoted(
            Map<String, Object> accepted, List<EntityField> promoted) {
        if (promoted.isEmpty()) {
            return accepted;
        }
        Map<String, Object> attrs = new LinkedHashMap<>(accepted);
        for (EntityField field : promoted) {
            attrs.remove(field.name());
        }
        return attrs;
    }

    private static List<Object> promotedJdbcValues(List<EntityField> promoted, Map<String, Object> accepted) {
        List<Object> values = new ArrayList<>(promoted.size());
        for (EntityField field : promoted) {
            values.add(accepted.get(field.name()));
        }
        return values;
    }

    private static String promotedSetClause(List<EntityField> promoted, String entityKey) {
        if (promoted.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (EntityField field : promoted) {
            sb.append(", ")
                    .append(requirePromotedColumn(field.hybridPromotedColumnName(entityKey)))
                    .append(" = ?");
        }
        return sb.toString();
    }

    private static String selectClause(List<EntityField> promoted, String entityKey) {
        if (promoted.isEmpty()) {
            return "attrs";
        }
        StringBuilder sb = new StringBuilder("attrs");
        for (EntityField field : promoted) {
            sb.append(", ").append(requirePromotedColumn(field.hybridPromotedColumnName(entityKey)));
        }
        return sb.toString();
    }

    private static String requirePromotedColumn(String column) {
        if (column == null || !PROMOTED_COLUMN.matcher(column).matches()) {
            throw new IllegalArgumentException("invalid promoted column name: " + column);
        }
        return column;
    }

    private RowMapper<Map<String, Object>> rowMapper(RenderedEntity entity, List<EntityField> promoted) {
        return (row, rowNum) -> mergeRow(entity, promoted, row);
    }

    private Map<String, Object> mergeRow(RenderedEntity entity, List<EntityField> promoted, ResultSet row)
            throws SQLException {
        Map<String, Object> merged = new LinkedHashMap<>(readAttrs(row.getString("attrs")));
        for (EntityField field : promoted) {
            String col = field.hybridPromotedColumnName(entity.entityKey());
            Object raw = row.getObject(col);
            if (row.wasNull()) {
                merged.put(field.name(), null);
            } else if (raw instanceof Number n && field.kind() == EntityFieldKind.INTEGER) {
                merged.put(field.name(), n.intValue());
            } else {
                merged.put(field.name(), raw);
            }
        }
        return merged;
    }

    private static void requireHybrid(RenderedEntity entity) {
        // Shared-record track: hybrid or document (DOC-1) — 共享表轨：hybrid 或 document
        if (!entity.sharedRecordStorage()) {
            throw new IllegalArgumentException(
                    "HybridEntityStore requires storageMode=hybrid|document, got: " + entity.storageMode());
        }
    }

    private static String effectiveTenant(RenderedEntity entity, String tenantId) {
        if (!entity.tenantScoped()) {
            return PLATFORM_TENANT;
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required when entity is tenantScoped");
        }
        return tenantId.trim();
    }

    private String writeAttrs(Map<String, Object> accepted) {
        try {
            return json.writeValueAsString(accepted);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("attrs cannot be written as JSON", ex);
        }
    }

    private Map<String, Object> readAttrs(String attrsJson) {
        try {
            LinkedHashMap<String, Object> raw = json.readValue(attrsJson, ATTRS_TYPE);
            return raw == null ? Map.of() : raw;
        } catch (JacksonException ex) {
            throw new IllegalStateException("attrs cannot be read as JSON", ex);
        }
    }
}
