package com.subjex.platform.app.declaration;

import com.subjex.platform.app.jdbc.PlatformTables;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JdbcDeclarationStore — 租户声明草稿修订的 JDBC 存取。
 * <p>
 * Inserts monotonic revisions per (tenant, kind, key). Promote flips {@code PROMOTED} and records {@code declaration_promote}; {@link #listPromotes} for history (Z5-2).
 * Spring-free class; bean in {@code PlatformWiring}.
 * 按 (租户, 种类, 键) 单调插入修订。晋升翻 {@code PROMOTED} 并写 {@code declaration_promote}（Z5-1）。
 * 无 Spring 注解；Bean 在 {@code PlatformWiring}。
 */
public final class JdbcDeclarationStore {

    public static final String DRAFT_STATE = "DRAFT";

    public static final String PROMOTED_STATE = "PROMOTED";

    /** Rolled back from being the effective tip — 已从生效尖端回滚。 */
    public static final String SUPERSEDED_STATE = "SUPERSEDED";

    private static final RowMapper<DeclarationRevision> ROW = (row, n) -> new DeclarationRevision(
            row.getString("tenant_id"),
            DeclarationKind.fromWire(row.getString("declaration_kind")),
            row.getString("declaration_key"),
            row.getInt("revision"),
            row.getString("yaml_body"),
            row.getString("draft_state"),
            row.getTimestamp("updated_at").toInstant(),
            row.getString("updated_by_subject_id"));

    private static final RowMapper<DeclarationPromote> PROMOTE_ROW = (row, n) -> new DeclarationPromote(
            row.getString("tenant_id"),
            DeclarationKind.fromWire(row.getString("declaration_kind")),
            row.getString("declaration_key"),
            row.getInt("revision"),
            row.getString("git_commit_sha"),
            row.getTimestamp("promoted_at").toInstant(),
            row.getString("promoted_by_subject_id"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcDeclarationStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Insert the next revision (max+1, starting at 1) as {@code DRAFT}.
     * 插入下一修订（max+1，从 1 起）为 {@code DRAFT}。
     */
    public DeclarationRevision saveDraft(
            String tenantId, DeclarationKind kind, String declarationKey, String yamlBody, String subjectId) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        String body = requireYaml(yamlBody);
        String sid = requireSubjectId(subjectId);
        int next = nextRevision(tid, k, key);
        var at = clock.instant();
        jdbc.update(
                """
                INSERT INTO declaration_revision
                  (tenant_id, declaration_kind, declaration_key, revision, yaml_body,
                   draft_state, updated_at, updated_by_subject_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tid,
                k.wireName(),
                key,
                next,
                body,
                DRAFT_STATE,
                PlatformTables.timestamp(at),
                sid);
        return new DeclarationRevision(tid, k, key, next, body, DRAFT_STATE, at, sid);
    }

    /** Latest revision for one key, if any — 某键的最新修订（若有）。 */
    public Optional<DeclarationRevision> latest(String tenantId, DeclarationKind kind, String declarationKey) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        List<DeclarationRevision> rows = jdbc.query(
                """
                SELECT tenant_id, declaration_kind, declaration_key, revision, yaml_body,
                       draft_state, updated_at, updated_by_subject_id
                FROM declaration_revision
                WHERE tenant_id = ? AND declaration_kind = ? AND declaration_key = ?
                ORDER BY revision DESC
                LIMIT 1
                """,
                ROW,
                tid,
                k.wireName(),
                key);
        return rows.stream().findFirst();
    }

    /**
     * Latest open {@code DRAFT} revision for one key, if any —
     * 某键最新未晋升 {@code DRAFT}（若有）。
     */
    public Optional<DeclarationRevision> latestDraft(String tenantId, DeclarationKind kind, String declarationKey) {
        return latestByState(tenantId, kind, declarationKey, DRAFT_STATE);
    }

    /**
     * Latest {@code PROMOTED} revision for one key, if any (hot-reload metadata) —
     * 某键最新 {@code PROMOTED}（若有；热加载元数据路径）。
     */
    public Optional<DeclarationRevision> latestPromoted(String tenantId, DeclarationKind kind, String declarationKey) {
        return latestByState(tenantId, kind, declarationKey, PROMOTED_STATE);
    }

    private Optional<DeclarationRevision> latestByState(
            String tenantId, DeclarationKind kind, String declarationKey, String draftState) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        List<DeclarationRevision> rows = jdbc.query(
                """
                SELECT tenant_id, declaration_kind, declaration_key, revision, yaml_body,
                       draft_state, updated_at, updated_by_subject_id
                FROM declaration_revision
                WHERE tenant_id = ? AND declaration_kind = ? AND declaration_key = ?
                  AND draft_state = ?
                ORDER BY revision DESC
                LIMIT 1
                """,
                ROW,
                tid,
                k.wireName(),
                key,
                draftState);
        return rows.stream().findFirst();
    }

    /** Latest revision per key for one kind in a tenant — 某租户某种类下每个键的最新修订。 */
    public List<DeclarationRevision> listLatest(String tenantId, DeclarationKind kind) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        return jdbc.query(
                """
                SELECT d.tenant_id, d.declaration_kind, d.declaration_key, d.revision, d.yaml_body,
                       d.draft_state, d.updated_at, d.updated_by_subject_id
                FROM declaration_revision d
                INNER JOIN (
                    SELECT declaration_key, MAX(revision) AS max_revision
                    FROM declaration_revision
                    WHERE tenant_id = ? AND declaration_kind = ?
                    GROUP BY declaration_key
                ) latest
                  ON d.declaration_key = latest.declaration_key
                 AND d.revision = latest.max_revision
                WHERE d.tenant_id = ? AND d.declaration_kind = ?
                ORDER BY d.declaration_key
                """,
                ROW,
                tid,
                k.wireName(),
                tid,
                k.wireName());
    }

    /** Full history for one key, newest first — 某键完整历史，新在前。 */
    public List<DeclarationRevision> listRevisions(String tenantId, DeclarationKind kind, String declarationKey) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        return jdbc.query(
                """
                SELECT tenant_id, declaration_kind, declaration_key, revision, yaml_body,
                       draft_state, updated_at, updated_by_subject_id
                FROM declaration_revision
                WHERE tenant_id = ? AND declaration_kind = ? AND declaration_key = ?
                ORDER BY revision DESC
                """,
                ROW,
                tid,
                k.wireName(),
                key);
    }

    /** One revision by number, if any — 按修订号取一条（若有）。 */
    public Optional<DeclarationRevision> findRevision(
            String tenantId, DeclarationKind kind, String declarationKey, int revision) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
        List<DeclarationRevision> rows = jdbc.query(
                """
                SELECT tenant_id, declaration_kind, declaration_key, revision, yaml_body,
                       draft_state, updated_at, updated_by_subject_id
                FROM declaration_revision
                WHERE tenant_id = ? AND declaration_kind = ? AND declaration_key = ? AND revision = ?
                """,
                ROW,
                tid,
                k.wireName(),
                key,
                revision);
        return rows.stream().findFirst();
    }

    /**
     * Mark one revision {@code PROMOTED}; fails if the row is missing —
     * 将一条修订标为 {@code PROMOTED}；行不存在则失败。
     */
    public void markPromoted(String tenantId, DeclarationKind kind, String declarationKey, int revision) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
        int updated = jdbc.update(
                """
                UPDATE declaration_revision
                SET draft_state = ?
                WHERE tenant_id = ? AND declaration_kind = ? AND declaration_key = ? AND revision = ?
                  AND draft_state = ?
                """,
                PROMOTED_STATE,
                tid,
                k.wireName(),
                key,
                revision,
                DRAFT_STATE);
        if (updated == 1) {
            return;
        }
        Optional<DeclarationRevision> existing = findRevision(tid, k, key, revision);
        if (existing.isPresent() && PROMOTED_STATE.equals(existing.get().draftState())) {
            throw new DeclarationAlreadyPromoted(
                    "declaration already promoted: " + k.wireName() + "/" + key + "@r" + revision);
        }
        throw new IllegalArgumentException(
                "declaration revision not found for promote: " + k.wireName() + "/" + key + "@r" + revision);
    }

    /**
     * Insert a promote audit row (unique on tenant/kind/key/revision) —
     * 插入晋升审计行（租户/种类/键/修订唯一）。
     */
    public void recordPromote(
            String tenantId,
            DeclarationKind kind,
            String declarationKey,
            int revision,
            String gitCommitSha,
            String subjectId) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
        if (gitCommitSha == null || gitCommitSha.isBlank()) {
            throw new IllegalArgumentException("gitCommitSha required");
        }
        String sid = requireSubjectId(subjectId);
        var at = clock.instant();
        jdbc.update(
                """
                INSERT INTO declaration_promote
                  (tenant_id, declaration_kind, declaration_key, revision,
                   git_commit_sha, promoted_at, promoted_by_subject_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                tid,
                k.wireName(),
                key,
                revision,
                gitCommitSha.trim(),
                PlatformTables.timestamp(at),
                sid);
    }

    /** Promote history for one key, newest revision first — 某键晋升历史，新修订在前。 */
    public List<DeclarationPromote> listPromotes(String tenantId, DeclarationKind kind, String declarationKey) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        return jdbc.query(
                """
                SELECT tenant_id, declaration_kind, declaration_key, revision,
                       git_commit_sha, promoted_at, promoted_by_subject_id
                FROM declaration_promote
                WHERE tenant_id = ? AND declaration_kind = ? AND declaration_key = ?
                ORDER BY revision DESC
                """,
                PROMOTE_ROW,
                tid,
                k.wireName(),
                key);
    }


    /**
     * Mark one {@code PROMOTED} revision {@code SUPERSEDED} (rollback tip) —
     * 将一条 {@code PROMOTED} 标为 {@code SUPERSEDED}（回滚尖端）。
     */
    public void markSuperseded(String tenantId, DeclarationKind kind, String declarationKey, int revision) {
        String tid = requireTenantId(tenantId);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        String key = requireKey(declarationKey);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
        int updated = jdbc.update(
                """
                UPDATE declaration_revision
                SET draft_state = ?
                WHERE tenant_id = ? AND declaration_kind = ? AND declaration_key = ? AND revision = ?
                  AND draft_state = ?
                """,
                SUPERSEDED_STATE,
                tid,
                k.wireName(),
                key,
                revision,
                PROMOTED_STATE);
        if (updated != 1) {
            throw new IllegalArgumentException(
                    "declaration revision not found for supersede: "
                            + k.wireName() + "/" + key + "@r" + revision);
        }
    }

    private int nextRevision(String tenantId, DeclarationKind kind, String key) {
        Integer max = jdbc.query(
                """
                SELECT MAX(revision) FROM declaration_revision
                WHERE tenant_id = ? AND declaration_kind = ? AND declaration_key = ?
                """,
                rs -> rs.next() ? rs.getObject(1, Integer.class) : null,
                tenantId,
                kind.wireName(),
                key);
        return (max == null ? 0 : max) + 1;
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

    private static String requireYaml(String yamlBody) {
        if (yamlBody == null || yamlBody.isBlank()) {
            throw new IllegalArgumentException("yamlBody required");
        }
        return yamlBody;
    }

    private static String requireSubjectId(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId required");
        }
        return subjectId.trim();
    }
}
