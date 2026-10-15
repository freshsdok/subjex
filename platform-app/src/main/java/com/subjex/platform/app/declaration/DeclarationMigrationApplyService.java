package com.subjex.platform.app.declaration;

import com.subjex.platform.app.entity.HybridPromoteColumnMigrator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * DeclarationMigrationApplyService — 对 REVIEWED 的实体迁移执行单条白名单 DDL（失败关闭校验）。
 * <p>
 * Accepts only {@code REVIEWED} + {@link DeclarationKind#ENTITY}. Validates {@code sqlText} fail-closed
 * (single statement; {@code ALTER TABLE} / {@code CREATE TABLE}; HY-2b also allows
 * {@code ALTER TABLE entity_record DROP COLUMN hp_*}). Rejects TRUNCATE and other DROP forms.
 * Runs in a DB transaction; on hybrid promote ADD, backfills attrs→column; on promote DROP, merges
 * column→attrs first. {@code markApplied} on success; {@code markFailed} then rethrow on execute failure.
 * 仅受理 REVIEWED 的 entity。HY-2b 允许升格列 DROP；升格 ADD 后回填；降格 DROP 前写回 attrs。
 */
public final class DeclarationMigrationApplyService {

    private static final Pattern DROP_WORD = Pattern.compile("(?i).*\\bDROP\\b.*", Pattern.DOTALL);
    private static final Pattern TRUNCATE_WORD = Pattern.compile("(?i).*\\bTRUNCATE\\b.*", Pattern.DOTALL);
    private static final Pattern ALTER_DROP =
            Pattern.compile("(?i).*\\bALTER\\s+TABLE\\b.*\\bDROP\\b.*", Pattern.DOTALL);

    private final JdbcDeclarationMigrationStore migrationStore;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final HybridPromoteColumnMigrator promoteMigrator;

    public DeclarationMigrationApplyService(
            JdbcDeclarationMigrationStore migrationStore, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this(migrationStore, jdbc, transactions, new ObjectMapper());
    }

    public DeclarationMigrationApplyService(
            JdbcDeclarationMigrationStore migrationStore,
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ObjectMapper json) {
        this.migrationStore = Objects.requireNonNull(migrationStore, "migrationStore");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.promoteMigrator = new HybridPromoteColumnMigrator(jdbc, Objects.requireNonNull(json, "json"));
    }

    /**
     * Apply one REVIEWED entity migration by id (tenant/kind/key must match) —
     * 按 id 执行一条 REVIEWED 实体迁移（须匹配租户/种类/键）。
     */
    public DeclarationMigration apply(
            String tenantId, DeclarationKind kind, String declarationKey, String migrationId) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        String id = requireMigrationId(migrationId);

        if (k != DeclarationKind.ENTITY) {
            throw new IllegalArgumentException(
                    "apply is only supported for entity migrations (got " + k.wireName() + ")");
        }

        DeclarationMigration existing = migrationStore
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("migration not found: " + id));
        if (!tid.equals(existing.tenantId())
                || existing.kind() != k
                || !key.equals(existing.declarationKey())) {
            throw new IllegalArgumentException("migration not found for tenant/kind/key: " + id);
        }
        if (!JdbcDeclarationMigrationStore.REVIEWED.equals(existing.status())) {
            throw new DeclarationMigrationNotReady(
                    "migration must be REVIEWED to apply (was " + existing.status() + "): " + id);
        }

        String sql = validateSqlForApply(existing.sqlText());

        try {
            DeclarationMigration applied = transactions.execute(status -> {
                Optional<String> hpColumn = HybridPromoteColumnMigrator.parsePromotedColumn(sql);
                if (HybridPromoteColumnMigrator.isHybridPromoteDrop(sql) && hpColumn.isPresent()) {
                    // Demote: merge column → attrs, then DROP — 降格：先写回 attrs 再 DROP
                    promoteMigrator.mergeIntoAttrs(key, hpColumn.get());
                }
                jdbc.execute(sql);
                if (HybridPromoteColumnMigrator.isHybridPromoteAdd(sql) && hpColumn.isPresent()) {
                    // Promote: ADD then attrs → column — 升格：ADD 后 attrs 回填列
                    promoteMigrator.backfillFromAttrs(key, hpColumn.get());
                }
                return migrationStore.markApplied(id);
            });
            return Objects.requireNonNull(applied, "apply transaction returned null");
        } catch (DeclarationMigrationNotReady | IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String message = truncateError(ex);
            try {
                migrationStore.markFailed(id, message);
            } catch (RuntimeException markEx) {
                ex.addSuppressed(markEx);
            }
            throw new DeclarationMigrationApplyFailed(
                    "migration apply failed: " + id + ": " + message, ex);
        }
    }

    /**
     * Fail-closed SQL gate for apply — 执行前失败关闭的 SQL 门禁。
     * <p>
     * Single statement; allowlist {@code ALTER TABLE} / {@code CREATE TABLE};
     * HY-2b: {@code ALTER TABLE entity_record DROP COLUMN hp_*} only other DROP form allowed.
     * Reject TRUNCATE and non-hp DROP.
     */
    static String validateSqlForApply(String sqlText) {
        if (sqlText == null || sqlText.isBlank()) {
            throw new IllegalArgumentException("sqlText required");
        }
        String trimmed = sqlText.trim();
        String body = trimmed;
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1).trim();
        }
        if (body.isEmpty()) {
            throw new IllegalArgumentException("sqlText required");
        }
        if (body.contains(";")) {
            throw new IllegalArgumentException("only a single SQL statement is allowed");
        }
        String upper = body.toUpperCase(Locale.ROOT);
        if (!(upper.startsWith("ALTER TABLE") || upper.startsWith("CREATE TABLE"))) {
            throw new IllegalArgumentException(
                    "sqlText must start with ALTER TABLE or CREATE TABLE");
        }
        if (TRUNCATE_WORD.matcher(body).matches()) {
            throw new IllegalArgumentException("sqlText must not contain TRUNCATE");
        }
        if (HybridPromoteColumnMigrator.isHybridPromoteDrop(body)) {
            return body;
        }
        if (ALTER_DROP.matcher(body).matches()) {
            throw new IllegalArgumentException("sqlText must not ALTER TABLE ... DROP");
        }
        if (DROP_WORD.matcher(body).matches()) {
            throw new IllegalArgumentException("sqlText must not contain DROP");
        }
        return body;
    }

    private static String truncateError(Throwable ex) {
        String raw;
        if (ex instanceof DataAccessException dae && dae.getMostSpecificCause() != null) {
            raw = dae.getMostSpecificCause().getMessage();
        } else {
            raw = ex.getMessage();
        }
        if (raw == null || raw.isBlank()) {
            raw = ex.getClass().getSimpleName();
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 2000) {
            return trimmed.substring(0, 2000);
        }
        return trimmed;
    }

    private static String requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required");
        }
        return tenantId.trim();
    }

    private static String requireKey(String declarationKey) {
        if (declarationKey == null || declarationKey.isBlank()) {
            throw new IllegalArgumentException("declarationKey required");
        }
        return declarationKey.trim();
    }

    private static String requireMigrationId(String migrationId) {
        if (migrationId == null || migrationId.isBlank()) {
            throw new IllegalArgumentException("migrationId required");
        }
        return migrationId.trim();
    }
}
