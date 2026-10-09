package com.subjex.platform.app.declaration;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * DeclarationMigrationEndpoint — 声明迁移队列 HTTP：列表 / 入队 / 审阅 / 执行 REVIEWED DDL。
 * <p>
 * GET needs {@code declaration.read}; POST enqueue/review/apply/cancel need {@code declaration.migrate}.
 * Apply runs fail-closed DDL for entity only ({@link DeclarationMigrationApplyService}).
 * Tenant grant required. Entity promote bind is in {@link DeclarationPromoteService}.
 * GET 要 {@code declaration.read}；POST 入队/审阅/执行/取消要 {@code declaration.migrate}。
 * 执行仅 entity、失败关闭。须租户授权。实体晋升绑定见 PromoteService。
 */
@RestController
public class DeclarationMigrationEndpoint {

    /** Same JSON base as drafts — 与草稿同一 JSON 根。 */
    public static final String PATH = JsonApi.BASE + "/declarations";

    private final JdbcDeclarationMigrationStore migrationStore;
    private final JdbcDeclarationStore declarationStore;
    private final DeclarationMigrationApplyService applyService;
    private final OperatorTenantAccess tenantAccess;
    private final OperatorActionAudit audit;

    public DeclarationMigrationEndpoint(
            JdbcDeclarationMigrationStore migrationStore,
            JdbcDeclarationStore declarationStore,
            DeclarationMigrationApplyService applyService,
            OperatorTenantAccess tenantAccess,
            OperatorActionAudit audit) {
        this.migrationStore = Objects.requireNonNull(migrationStore, "migrationStore");
        this.declarationStore = Objects.requireNonNull(declarationStore, "declarationStore");
        this.applyService = Objects.requireNonNull(applyService, "applyService");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** List migration queue for kind/key — 列出某 kind/key 的迁移队列。 */
    @GetMapping(PATH + "/{kind}/{key}/migrations")
    public MigrationsDocument list(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        List<MigrationDocument> migrations =
                migrationStore.list(tid, k, key).stream().map(DeclarationMigrationEndpoint::document).toList();
        return new MigrationsDocument(migrations);
    }

    /** Enqueue PENDING DDL (entity only) — 入队 PENDING DDL（仅实体）。 */
    @PostMapping(PATH + "/{kind}/{key}/migrations")
    public MigrationDocument enqueue(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody EnqueueRequest body) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        if (body == null || body.sqlText() == null || body.sqlText().isBlank()) {
            throw new IllegalArgumentException("sqlText required");
        }
        int revision;
        if (body.revision() == null) {
            revision = declarationStore
                    .latest(tid, k, key)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
                    .revision();
        } else {
            if (body.revision() < 1) {
                throw new IllegalArgumentException("revision must be >= 1");
            }
            declarationStore
                    .findRevision(tid, k, key, body.revision())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            revision = body.revision();
        }
        DeclarationMigration row =
                migrationStore.enqueue(tid, k, key, revision, body.sqlText(), operator.subjectId());
        String target = k.wireName() + "/" + key + "@" + revision + "#" + row.migrationId();
        audit.record(operator, "declaration.migrate.enqueue", target, AuditOutcome.ALLOWED);
        return document(row);
    }

    /** Mark PENDING -> REVIEWED — 将 PENDING 标为 REVIEWED。 */
    @PostMapping(PATH + "/{kind}/{key}/migrations/{id}/review")
    public MigrationDocument review(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @PathVariable("id") String id,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        DeclarationMigration existing = migrationStore
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tid.equals(existing.tenantId())
                || existing.kind() != k
                || !key.equals(existing.declarationKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        DeclarationMigration reviewed = migrationStore.markReviewed(id);
        String target = k.wireName() + "/" + key + "#" + reviewed.migrationId();
        audit.record(operator, "declaration.migrate.review", target, AuditOutcome.ALLOWED);
        return document(reviewed);
    }


    /** Apply REVIEWED DDL via fail-closed whitelist — 执行 REVIEWED DDL（白名单失败关闭）。 */
    @PostMapping(PATH + "/{kind}/{key}/migrations/{id}/apply")
    public MigrationDocument apply(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @PathVariable("id") String id,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        DeclarationMigration existing = migrationStore
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tid.equals(existing.tenantId())
                || existing.kind() != k
                || !key.equals(existing.declarationKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        DeclarationMigration applied = applyService.apply(tid, k, key, id);
        String target = k.wireName() + "/" + key + "#" + applied.migrationId();
        audit.record(operator, "declaration.migrate.apply", target, AuditOutcome.ALLOWED);
        return document(applied);
    }


    /** Cancel open migration job — 取消未完成迁移任务。 */
    @PostMapping(PATH + "/{kind}/{key}/migrations/{id}/cancel")
    public MigrationDocument cancel(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @PathVariable("id") String id,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        DeclarationMigration existing = migrationStore
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tid.equals(existing.tenantId())
                || existing.kind() != k
                || !key.equals(existing.declarationKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        DeclarationMigration cancelled = migrationStore.markCancelled(id);
        String target = k.wireName() + "/" + key + "#" + cancelled.migrationId();
        audit.record(operator, "declaration.migrate.cancel", target, AuditOutcome.ALLOWED);
        return document(cancelled);
    }

    private String requireTenant(OperatorPrincipal operator, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required");
        }
        String tid = tenantId.trim();
        tenantAccess.requireGranted(operator, tid);
        return tid;
    }

    private static MigrationDocument document(DeclarationMigration row) {
        return new MigrationDocument(
                row.migrationId(),
                row.tenantId(),
                row.kind().wireName(),
                row.declarationKey(),
                row.declarationRevision(),
                row.sqlText(),
                row.status(),
                row.createdAt(),
                row.createdBySubjectId(),
                row.updatedAt(),
                row.appliedAt(),
                row.errorMessage());
    }

    /** Enqueue body — 入队正文（省略 revision 则绑最新修订）。 */
    public record EnqueueRequest(Integer revision, String sqlText) {}

    /** MigrationsDocument — 迁移列表。 */
    public record MigrationsDocument(List<MigrationDocument> migrations) {}

    /** MigrationDocument — 一条迁移队列行。 */
    public record MigrationDocument(
            String migrationId,
            String tenantId,
            String declarationKind,
            String declarationKey,
            int declarationRevision,
            String sqlText,
            String status,
            Instant createdAt,
            String createdBySubjectId,
            Instant updatedAt,
            Instant appliedAt,
            String errorMessage) {}
}
