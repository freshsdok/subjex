package com.subjex.platform.app.declaration;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.EntityField;
import com.subjex.entity.declare.EntityMigrationGenerator;
import com.subjex.entity.declare.EntityRenderer;
import com.subjex.entity.declare.EntityStorageMode;
import com.subjex.entity.declare.RenderedEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * DeclarationMigrationAutoEnqueueService — 实体草稿保存后自动入队 ALTER ADD / CREATE（相对 PROMOTED 或 classpath 基线）。
 * <p>
 * On entity draft save: compare draft fields to latest {@code PROMOTED} (else classpath sample, else empty).
 * Brand-new (empty baseline) → one PENDING {@code CREATE TABLE}. Added columns → one PENDING
 * {@code ALTER TABLE … ADD COLUMN} each. Idempotent per revision+normalized sqlText (skip non-CANCELLED
 * duplicates). Does not APPLY; does not invent DROP/RENAME; refuses unsafe type / tableName / PK changes
 * before the draft row is inserted. Form/flow ignored. Manual enqueue UI remains available.
 * 实体草稿保存：相对最新 PROMOTED（否则 classpath，否则空基线）差出加列。新表 CREATE；加列逐条 ALTER ADD。
 * 同修订同 SQL 不重复入队；不自动执行；不造 DROP/RENAME；危险类型/表名/主键变更在落库前失败关闭。
 */
public final class DeclarationMigrationAutoEnqueueService {

    private final JdbcDeclarationStore declarationStore;
    private final JdbcDeclarationMigrationStore migrationStore;
    private final EntityCatalog entityCatalog;
    private final EntityRenderer entityRenderer;
    private final EntityMigrationGenerator migrationGenerator;

    public DeclarationMigrationAutoEnqueueService(
            JdbcDeclarationStore declarationStore,
            JdbcDeclarationMigrationStore migrationStore,
            EntityCatalog entityCatalog) {
        this(
                declarationStore,
                migrationStore,
                entityCatalog,
                new EntityRenderer(),
                new EntityMigrationGenerator());
    }

    DeclarationMigrationAutoEnqueueService(
            JdbcDeclarationStore declarationStore,
            JdbcDeclarationMigrationStore migrationStore,
            EntityCatalog entityCatalog,
            EntityRenderer entityRenderer,
            EntityMigrationGenerator migrationGenerator) {
        this.declarationStore = Objects.requireNonNull(declarationStore, "declarationStore");
        this.migrationStore = Objects.requireNonNull(migrationStore, "migrationStore");
        this.entityCatalog = Objects.requireNonNull(entityCatalog, "entityCatalog");
        this.entityRenderer = Objects.requireNonNull(entityRenderer, "entityRenderer");
        this.migrationGenerator = Objects.requireNonNull(migrationGenerator, "migrationGenerator");
    }

    /**
     * Plan DDL for an entity YAML against the current baseline (may throw) —
     * 相对当前基线规划实体 YAML 的 DDL（危险差异抛错）。非实体调用方勿用。
     */
    public List<String> planForEntityYaml(String tenantId, String declarationKey, String yamlBody) {
        String key = requireKey(declarationKey);
        RenderedEntity draft = entityRenderer.render(yamlBody);
        if (!key.equals(draft.entityKey())) {
            throw new IllegalArgumentException("entityKey must match path key");
        }
        Optional<RenderedEntity> baseline = resolveBaseline(tenantId, key);
        return planSql(draft, baseline.orElse(null));
    }

    /**
     * After a draft revision is saved: enqueue the planned PENDING jobs —
     * 草稿修订已保存后：把已规划的 PENDING 任务入队（幂等）。
     *
     * @return newly inserted jobs (skipped duplicates omitted) / 新插入的任务（跳过的重复不计）
     */
    public List<DeclarationMigration> enqueuePlanned(DeclarationRevision saved, List<String> plannedSql) {
        Objects.requireNonNull(saved, "saved");
        if (saved.kind() != DeclarationKind.ENTITY) {
            return List.of();
        }
        if (plannedSql == null || plannedSql.isEmpty()) {
            return List.of();
        }
        List<DeclarationMigration> existing =
                migrationStore.listForRevision(
                        saved.tenantId(), DeclarationKind.ENTITY, saved.declarationKey(), saved.revision());
        Set<String> already = new LinkedHashSet<>();
        for (DeclarationMigration row : existing) {
            if (JdbcDeclarationMigrationStore.CANCELLED.equals(row.status())) {
                continue;
            }
            already.add(normalizeSql(row.sqlText()));
        }
        List<DeclarationMigration> created = new ArrayList<>();
        for (String sql : plannedSql) {
            if (sql == null || sql.isBlank()) {
                continue;
            }
            String normalized = normalizeSql(sql);
            if (already.contains(normalized)) {
                continue;
            }
            DeclarationMigration job = migrationStore.enqueue(
                    saved.tenantId(),
                    DeclarationKind.ENTITY,
                    saved.declarationKey(),
                    saved.revision(),
                    sql.trim(),
                    saved.updatedBySubjectId());
            already.add(normalized);
            created.add(job);
        }
        return List.copyOf(created);
    }

    /**
     * Convenience: plan + enqueue for a saved entity draft —
     * 便捷：对已保存实体草稿规划并入队。
     */
    public List<DeclarationMigration> afterDraftSaved(DeclarationRevision saved) {
        Objects.requireNonNull(saved, "saved");
        if (saved.kind() != DeclarationKind.ENTITY) {
            return List.of();
        }
        List<String> planned =
                planForEntityYaml(saved.tenantId(), saved.declarationKey(), saved.yamlBody());
        return enqueuePlanned(saved, planned);
    }

    /**
     * Baseline schema: latest PROMOTED, else classpath sample, else empty —
     * 基线：最新 PROMOTED → classpath 样例 → 空（全新实体）。
     */
    Optional<RenderedEntity> resolveBaseline(String tenantId, String entityKey) {
        Optional<DeclarationRevision> promoted =
                declarationStore.latestPromoted(tenantId, DeclarationKind.ENTITY, entityKey);
        if (promoted.isPresent()) {
            return Optional.of(entityRenderer.render(promoted.get().yamlBody()));
        }
        return entityCatalog.find(entityKey);
    }

    /**
     * Plan CREATE or ALTER ADD statements; fail-closed on unsafe diffs —
     * 规划 CREATE 或 ALTER ADD；危险差异失败关闭。
     */
    List<String> planSql(RenderedEntity draft, RenderedEntity baseline) {
        // HY-2 / DOC-1: shared-record (hybrid|document) plans promote ADDs; table track CREATE/ALTER.
        // HY-2 / DOC-1：共享表轨规划升格 ADD；table 轨仍 CREATE/ALTER 实体表。
        if (draft.sharedRecordStorage()) {
            return planHybridPromoteSql(draft, baseline);
        }
        if (baseline != null && baseline.sharedRecordStorage()) {
            throw new IllegalArgumentException(
                    "entity draft switches storageMode from "
                            + baseline.storageMode().name().toLowerCase()
                            + " to table; refuse auto migration");
        }
        if (baseline == null) {
            return List.of(migrationGenerator.createTableStatement(draft));
        }
        if (!baseline.tableName().equals(draft.tableName())) {
            throw new IllegalArgumentException(
                    "entity draft changes tableName; refuse auto migration (got "
                            + draft.tableName()
                            + ", baseline "
                            + baseline.tableName()
                            + ")");
        }
        String baselinePk = baseline.primaryKey().columnName();
        String draftPk = draft.primaryKey().columnName();
        if (!baselinePk.equals(draftPk)) {
            throw new IllegalArgumentException(
                    "entity draft changes primary key column; refuse auto migration (got "
                            + draftPk
                            + ", baseline "
                            + baselinePk
                            + ")");
        }

        Map<String, EntityField> baselineByColumn = new LinkedHashMap<>();
        for (EntityField field : baseline.fields()) {
            baselineByColumn.put(field.columnName(), field);
        }
        Set<String> existingColumns = new LinkedHashSet<>(baselineByColumn.keySet());
        if (baseline.tenantScoped()) {
            existingColumns.add(EntityMigrationGenerator.TENANT_COLUMN);
        }

        List<String> adds = new ArrayList<>();
        for (EntityField field : draft.fields()) {
            String column = field.columnName();
            EntityField prior = baselineByColumn.get(column);
            if (prior != null) {
                requireCompatibleField(prior, field);
                continue;
            }
            if (existingColumns.contains(column)) {
                // Physical tenant_id already implied by scoped baseline — skip ADD.
                // 隔离基线已隐含物理 tenant_id——跳过 ADD。
                continue;
            }
            adds.add(migrationGenerator.addColumnStatement(draft.tableName(), field));
        }
        return List.copyOf(adds);
    }

    /**
     * Shared-record DDL (hybrid|document): enqueue {@code entity_record} ADD only for new hybrid {@code promote: true} —
     * 共享表 DDL：仅 hybrid 新升格字段入队 entity_record ADD；document 不入队。
     * <p>
     * No per-entity CREATE TABLE (shared store). Demote / DROP not planned this slice.
     * 无实体表 CREATE（共享存储）。降格 / DROP 本片不做。
     */
    List<String> planHybridPromoteSql(RenderedEntity draft, RenderedEntity baseline) {
        if (baseline != null) {
            if (baseline.storageMode() == EntityStorageMode.TABLE) {
                throw new IllegalArgumentException(
                        "entity draft switches storageMode from table to "
                                + draft.storageMode().name().toLowerCase()
                                + "; refuse auto migration");
            }
            // DOC-1: hybrid→document refuses while any field is still promote:true (no demote).
            // DOC-1：仍有升格字段时拒绝 hybrid→document（无降格）。
            if (baseline.hybridStorage()
                    && draft.documentStorage()
                    && baseline.fields().stream().anyMatch(EntityField::promote)) {
                throw new IllegalArgumentException(
                        "entity draft switches storageMode from hybrid to document while promoted fields exist; refuse auto migration");
            }
            if (!baseline.tableName().equals(draft.tableName())) {
                throw new IllegalArgumentException(
                        "entity draft changes tableName; refuse auto migration (got "
                                + draft.tableName()
                                + ", baseline "
                                + baseline.tableName()
                                + ")");
            }
            String baselinePk = baseline.primaryKey().columnName();
            String draftPk = draft.primaryKey().columnName();
            if (!baselinePk.equals(draftPk)) {
                throw new IllegalArgumentException(
                        "entity draft changes primary key column; refuse auto migration (got "
                                + draftPk
                                + ", baseline "
                                + baselinePk
                                + ")");
            }
        }
        Map<String, EntityField> baselineByName = new LinkedHashMap<>();
        if (baseline != null) {
            for (EntityField field : baseline.fields()) {
                baselineByName.put(field.name(), field);
            }
        }
        // Document mode: attrs-only; never enqueue hp_* ADD — document 仅 attrs，不入队升格列
        if (draft.documentStorage()) {
            for (EntityField field : draft.fields()) {
                EntityField prior = baselineByName.get(field.name());
                if (prior != null) {
                    requireCompatibleField(prior, field);
                }
            }
            return List.of();
        }
        List<String> adds = new ArrayList<>();
        for (EntityField field : draft.fields()) {
            EntityField prior = baselineByName.get(field.name());
            if (prior != null) {
                requireCompatibleField(prior, field);
                if (prior.promote() && !field.promote()) {
                    throw new IllegalArgumentException(
                            "entity draft demotes promoted field "
                                    + field.name()
                                    + "; refuse auto migration (DROP not supported this slice)");
                }
                if (prior.promote() && field.promote()) {
                    continue; // column already planned/applied under prior promote
                }
                if (!prior.promote() && field.promote()) {
                    adds.add(migrationGenerator.addHybridPromotedColumnStatement(draft.entityKey(), field));
                }
                continue;
            }
            // New field
            if (field.promote()) {
                adds.add(migrationGenerator.addHybridPromotedColumnStatement(draft.entityKey(), field));
            }
        }
        return List.copyOf(adds);
    }

    private static void requireCompatibleField(EntityField baseline, EntityField draft) {
        if (baseline.kind() != draft.kind()) {
            throw new IllegalArgumentException(
                    "entity draft changes field kind for column "
                            + draft.columnName()
                            + " ("
                            + baseline.kind()
                            + " → "
                            + draft.kind()
                            + "); refuse auto migration");
        }
        String baselineType = EntityMigrationGenerator.sqlType(baseline);
        String draftType = EntityMigrationGenerator.sqlType(draft);
        if (!baselineType.equals(draftType)) {
            throw new IllegalArgumentException(
                    "entity draft changes SQL type for column "
                            + draft.columnName()
                            + " ("
                            + baselineType
                            + " → "
                            + draftType
                            + "); refuse auto migration");
        }
    }

    static String normalizeSql(String sqlText) {
        if (sqlText == null) {
            return "";
        }
        String trimmed = sqlText.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String requireKey(String declarationKey) {
        if (declarationKey == null || declarationKey.isBlank()) {
            throw new IllegalArgumentException("declarationKey required");
        }
        return declarationKey.trim();
    }
}
