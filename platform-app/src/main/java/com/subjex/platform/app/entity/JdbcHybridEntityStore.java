package com.subjex.platform.app.entity;

import com.subjex.entity.declare.EntityStorageMode;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.jdbc.PlatformTables;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * JdbcHybridEntityStore — JDBC 实现：读写共享表 {@code entity_record}（ES-1 / ADR 0002）。
 * <p>
 * Core columns: tenant_id, entity_key, record_id, record_state, attrs (JSON text), created_at, updated_at.
 * Non-scoped entities stamp tenant_id as empty string (platform sentinel). Rejects storageMode=table.
 * 核心列见上；非隔离实体 tenant_id 盖空串；拒绝 table 轨调用。
 */
public final class JdbcHybridEntityStore implements HybridEntityStore {

    /** Shared hybrid table — 共享混合表。 */
    public static final String TABLE = "entity_record";

    /** Default lifecycle state — 默认生命周期状态。 */
    public static final String STATE_ACTIVE = "ACTIVE";

    /** Tenant sentinel for non-scoped entities — 非隔离实体的租户占位。 */
    public static final String PLATFORM_TENANT = "";

    private static final TypeReference<LinkedHashMap<String, Object>> ATTRS_TYPE =
            new TypeReference<LinkedHashMap<String, Object>>() {};

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper json;
    private final GenericEntityStore validator;

    public JdbcHybridEntityStore(JdbcTemplate jdbc, Clock clock, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
        this.validator = new GenericEntityStore(jdbc);
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
        String attrsJson = writeAttrs(accepted);
        Timestamp now = PlatformTables.timestamp(clock.instant());
        int updated = jdbc.update(
                """
                UPDATE entity_record
                   SET record_state = ?, attrs = ?, updated_at = ?
                 WHERE tenant_id = ? AND entity_key = ? AND record_id = ?
                """,
                STATE_ACTIVE,
                attrsJson,
                now,
                effectiveTenant,
                entity.entityKey(),
                recordId);
        if (updated == 0) {
            try {
                jdbc.update(
                        """
                        INSERT INTO entity_record
                          (tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        effectiveTenant,
                        entity.entityKey(),
                        recordId,
                        STATE_ACTIVE,
                        attrsJson,
                        now,
                        now);
            } catch (DuplicateKeyException raced) {
                jdbc.update(
                        """
                        UPDATE entity_record
                           SET record_state = ?, attrs = ?, updated_at = ?
                         WHERE tenant_id = ? AND entity_key = ? AND record_id = ?
                        """,
                        STATE_ACTIVE,
                        attrsJson,
                        now,
                        effectiveTenant,
                        entity.entityKey(),
                        recordId);
            }
        }
    }

    @Override
    public Optional<Map<String, Object>> findById(RenderedEntity entity, String id, String tenantId) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(id, "id");
        requireHybrid(entity);
        String effectiveTenant = effectiveTenant(entity, tenantId);
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT attrs FROM entity_record
                 WHERE tenant_id = ? AND entity_key = ? AND record_id = ?
                """,
                attrsMapper(),
                effectiveTenant,
                entity.entityKey(),
                id);
        return rows.stream().findFirst();
    }

    @Override
    public List<Map<String, Object>> list(RenderedEntity entity, int limit, String tenantId) {
        Objects.requireNonNull(entity, "entity");
        requireHybrid(entity);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        String effectiveTenant = effectiveTenant(entity, tenantId);
        return jdbc.query(
                """
                SELECT attrs FROM entity_record
                 WHERE tenant_id = ? AND entity_key = ?
                 ORDER BY record_id ASC
                 LIMIT ?
                """,
                attrsMapper(),
                effectiveTenant,
                entity.entityKey(),
                limit);
    }

    private RowMapper<Map<String, Object>> attrsMapper() {
        return (row, rowNum) -> readAttrs(row.getString("attrs"));
    }

    private static void requireHybrid(RenderedEntity entity) {
        if (entity.storageMode() != EntityStorageMode.HYBRID) {
            throw new IllegalArgumentException(
                    "HybridEntityStore requires storageMode=hybrid, got: " + entity.storageMode());
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
