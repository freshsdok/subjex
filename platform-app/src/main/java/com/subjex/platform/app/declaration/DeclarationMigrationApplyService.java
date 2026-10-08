package com.subjex.platform.app.declaration;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DeclarationMigrationApplyService — 对 REVIEWED 的实体迁移执行单条白名单 DDL（失败关闭校验）。
 * <p>
 * Accepts only {@code REVIEWED} + {@link DeclarationKind#ENTITY}. Validates {@code sqlText} fail-closed
 * (single statement; {@code ALTER TABLE} / {@code CREATE TABLE} only; rejects DROP/TRUNCATE/ALTER…DROP).
 * Runs in a DB transaction; {@code markApplied} on success; {@code markFailed} then rethrow on execute failure.
 * Form/flow apply is rejected (400). Promote cutover bind is separate ({@link DeclarationPromoteService}).
 * 仅受理 REVIEWED 的 entity。校验失败关闭；成功 markApplied；执行失败 markFailed 再抛出。
 */
public final class DeclarationMigrationApplyService {

    private static final Pattern DROP_WORD = Pattern.compile("(?i).*\\bDROP\\b.*", Pattern.DOTALL);
    private static final Pattern TRUNCATE_WORD = Pattern.compile("(?i).*\\bTRUNCATE\\b.*", Pattern.DOTALL);
    private static final Pattern ALTER_DROP =
            Pattern.compile("(?i).*\\bALTER\\s+TABLE\\b.*\\bDROP\\b.*", Pattern.DOTALL);

    private final JdbcDeclarationMigrationStore migrationStore;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public DeclarationMigrationApplyService(
            JdbcDeclarationMigrationStore migrationStore, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.migrationStore = Objects.requireNonNull(migrationStore, "migrationStore");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
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
                jdbc.execute(sql);
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
     * Single statement; allowlist prefix {@code ALTER TABLE} / {@code CREATE TABLE};
     * reject DROP / TRUNCATE / {@code ALTER TABLE … DROP}.
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
