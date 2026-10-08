package com.subjex.platform.app.declaration;

import com.subjex.platform.app.jdbc.PlatformTables;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcDeclarationMigrationStore — 声明迁移队列 JDBC 存取（入队 / 列表 / 审阅 / 标记结果）。
 * <p>
 * Does not execute {@code sql_text} (apply is {@link DeclarationMigrationApplyService}). Status:
 * enqueue → PENDING; markReviewed → REVIEWED; markApplied / markFailed; markCancelled.
 * Spring-free; bean in {@code PlatformWiring}.
 * 不执行 {@code sql_text}（执行见 ApplyService）。入队 PENDING；审阅 REVIEWED；APPLIED/FAILED；CANCELLED。无 Spring 注解。
 */
public final class JdbcDeclarationMigrationStore {

    public static final String PENDING = "PENDING";
    public static final String REVIEWED = "REVIEWED";
    public static final String APPLIED = "APPLIED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private static final RowMapper<DeclarationMigration> ROW = (row, n) -> new DeclarationMigration(
            row.getString("migration_id"),
            row.getString("tenant_id"),
            DeclarationKind.fromWire(row.getString("declaration_kind")),
            row.getString("declaration_key"),
            row.getInt("declaration_revision"),
            row.getString("sql_text"),
            row.getString("status"),
            row.getTimestamp("created_at").toInstant(),
            row.getString("created_by_subject_id"),
            row.getTimestamp("updated_at").toInstant(),
            row.getTimestamp("applied_at") == null ? null : row.getTimestamp("applied_at").toInstant(),
            row.getString("error_message"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcDeclarationMigrationStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Insert a PENDING migration job — 插入一条 PENDING 迁移任务。
     */
    public DeclarationMigration enqueue(
            String tenantId,
            DeclarationKind kind,
            String declarationKey,
            int declarationRevision,
            String sqlText,
            String subjectId) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        if (declarationRevision < 1) {
            throw new IllegalArgumentException("declarationRevision must be >= 1");
        }
        String sql = requireSql(sqlText);
        String sid = requireSubjectId(subjectId);
        String id = UUID.randomUUID().toString();
        var at = clock.instant();
        jdbc.update(
                """
                INSERT INTO declaration_migration
                  (migration_id, tenant_id, declaration_kind, declaration_key, declaration_revision,
                   sql_text, status, created_at, created_by_subject_id, updated_at, applied_at, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)
                """,
                id,
                tid,
                k.wireName(),
                key,
                declarationRevision,
                sql,
                PENDING,
                PlatformTables.timestamp(at),
                sid,
                PlatformTables.timestamp(at));
        return new DeclarationMigration(
                id, tid, k, key, declarationRevision, sql, PENDING, at, sid, at, null, null);
    }

    /** List jobs for one key, newest created first — 某键迁移列表，新创建在前。 */
    public List<DeclarationMigration> list(String tenantId, DeclarationKind kind, String declarationKey) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        return jdbc.query(
                """
                SELECT migration_id, tenant_id, declaration_kind, declaration_key, declaration_revision,
                       sql_text, status, created_at, created_by_subject_id, updated_at, applied_at, error_message
                FROM declaration_migration
                WHERE tenant_id = ? AND declaration_kind = ? AND declaration_key = ?
                ORDER BY created_at DESC, migration_id DESC
                """,
                ROW,
                tid,
                k.wireName(),
                key);
    }


    /** List jobs for one key + revision — 某键某修订的迁移列表。 */
    public List<DeclarationMigration> listForRevision(
            String tenantId, DeclarationKind kind, String declarationKey, int declarationRevision) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        if (declarationRevision < 1) {
            throw new IllegalArgumentException("declarationRevision must be >= 1");
        }
        return jdbc.query(
                """
                SELECT migration_id, tenant_id, declaration_kind, declaration_key, declaration_revision,
                       sql_text, status, created_at, created_by_subject_id, updated_at, applied_at, error_message
                FROM declaration_migration
                WHERE tenant_id = ? AND declaration_kind = ? AND declaration_key = ?
                  AND declaration_revision = ?
                ORDER BY created_at DESC, migration_id DESC
                """,
                ROW,
                tid,
                k.wireName(),
                key,
                declarationRevision);
    }

    /** One job by id, if any — 按 id 取一条（若有）。 */
    public Optional<DeclarationMigration> findById(String migrationId) {
        String id = requireMigrationId(migrationId);
        List<DeclarationMigration> rows = jdbc.query(
                """
                SELECT migration_id, tenant_id, declaration_kind, declaration_key, declaration_revision,
                       sql_text, status, created_at, created_by_subject_id, updated_at, applied_at, error_message
                FROM declaration_migration
                WHERE migration_id = ?
                """,
                ROW,
                id);
        return rows.stream().findFirst();
    }

    /**
     * PENDING → REVIEWED; fails if missing or wrong status —
     * PENDING 翻 REVIEWED；行不存在或状态不对则失败。
     */
    public DeclarationMigration markReviewed(String migrationId) {
        return transition(migrationId, PENDING, REVIEWED, null, null);
    }

    /**
     * REVIEWED → APPLIED (for MQ-2); clears error_message —
     * REVIEWED 翻 APPLIED（供 MQ-2）；清空 error_message。
     */
    public DeclarationMigration markApplied(String migrationId) {
        return transition(migrationId, REVIEWED, APPLIED, clock.instant(), null);
    }

    /**
     * REVIEWED → FAILED with error message (for MQ-2) —
     * REVIEWED 翻 FAILED 并写下错误（供 MQ-2）。
     */
    public DeclarationMigration markFailed(String migrationId, String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("errorMessage required");
        }
        String trimmed = errorMessage.trim();
        if (trimmed.length() > 2000) {
            trimmed = trimmed.substring(0, 2000);
        }
        return transition(migrationId, REVIEWED, FAILED, null, trimmed);
    }

    /**
     * PENDING or REVIEWED → CANCELLED —
     * PENDING 或 REVIEWED 翻 CANCELLED。
     */
    public DeclarationMigration markCancelled(String migrationId) {
        String id = requireMigrationId(migrationId);
        DeclarationMigration existing = findById(id)
                .orElseThrow(() -> new IllegalArgumentException("migration not found: " + id));
        if (!PENDING.equals(existing.status()) && !REVIEWED.equals(existing.status())) {
            throw new IllegalArgumentException(
                    "migration not cancellable from status " + existing.status() + ": " + id);
        }
        var at = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE declaration_migration
                SET status = ?, updated_at = ?
                WHERE migration_id = ? AND status IN (?, ?)
                """,
                CANCELLED,
                PlatformTables.timestamp(at),
                id,
                PENDING,
                REVIEWED);
        if (updated != 1) {
            throw new IllegalArgumentException("migration cancel raced or missing: " + id);
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("migration missing after cancel: " + id));
    }

    private DeclarationMigration transition(
            String migrationId, String fromStatus, String toStatus, Instant appliedAt, String errorMessage) {
        String id = requireMigrationId(migrationId);
        DeclarationMigration existing = findById(id)
                .orElseThrow(() -> new IllegalArgumentException("migration not found: " + id));
        if (!fromStatus.equals(existing.status())) {
            throw new IllegalArgumentException(
                    "migration expected status " + fromStatus + " but was " + existing.status() + ": " + id);
        }
        var at = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE declaration_migration
                SET status = ?, updated_at = ?, applied_at = ?, error_message = ?
                WHERE migration_id = ? AND status = ?
                """,
                toStatus,
                PlatformTables.timestamp(at),
                PlatformTables.timestamp(appliedAt),
                errorMessage,
                id,
                fromStatus);
        if (updated != 1) {
            throw new IllegalArgumentException("migration transition raced or missing: " + id);
        }
        return findById(id).orElseThrow(() -> new IllegalStateException("migration missing after transition: " + id));
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

    private static String requireSql(String sqlText) {
        if (sqlText == null || sqlText.isBlank()) {
            throw new IllegalArgumentException("sqlText required");
        }
        String trimmed = sqlText.trim();
        if (trimmed.length() > 16000) {
            throw new IllegalArgumentException("sqlText too long (max 16000)");
        }
        return trimmed;
    }

    private static String requireSubjectId(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId required");
        }
        return subjectId.trim();
    }

    private static String requireMigrationId(String migrationId) {
        if (migrationId == null || migrationId.isBlank()) {
            throw new IllegalArgumentException("migrationId required");
        }
        return migrationId.trim();
    }
}
