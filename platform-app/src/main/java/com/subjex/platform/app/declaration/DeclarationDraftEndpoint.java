package com.subjex.platform.app.declaration;

import com.subjex.entity.declare.EntityRenderer;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.FormRenderer;
import com.subjex.form.render.RenderedForm;
import com.subjex.page.declare.PageRenderer;
import com.subjex.page.declare.RenderedFlow;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * DeclarationDraftEndpoint — 租户声明草稿 HTTP：列表 / 最新 / 历史 / 保存 / 生效摘要。
 * <p>
 * Query {@code tenantId} required (400 if blank). Operator must hold a tenant grant.
 * GET needs {@code declaration.read}; PUT needs {@code declaration.write}. YAML validated
 * with EntityRenderer / FormRenderer / PageRenderer before save, then RT-3 pick-only
 * permission catalog + form effect whitelist ({@code audit.write}|{@code task.enqueue}).
 * Entity PUT also auto-enqueues PENDING ALTER ADD / CREATE via
 * {@link DeclarationMigrationAutoEnqueueService} (RT-4); still PENDING until review/apply.
 * 查询参数 {@code tenantId} 必填（空则 400）。操作员须有租户授权。
 * GET 要 {@code declaration.read}；PUT 要 {@code declaration.write}。保存前渲染校验 YAML，并做权限目录与表单副作用白名单（RT-3）。
 * 实体 PUT 会自动入队 PENDING 加列/建表（RT-4）；仍须审阅后执行。
 */
@RestController
public class DeclarationDraftEndpoint {

    /** JSON base for declaration drafts — 声明草稿 JSON 根路径。 */
    public static final String PATH = JsonApi.BASE + "/declarations";

    private final JdbcDeclarationStore store;
    private final EffectiveDeclarationService effective;
    private final DeclarationMigrationAutoEnqueueService migrationAutoEnqueue;
    private final OperatorTenantAccess tenantAccess;
    private final EntityRenderer entityRenderer = new EntityRenderer();
    private final FormRenderer formRenderer = new FormRenderer();
    private final PageRenderer pageRenderer = new PageRenderer();

    public DeclarationDraftEndpoint(
            JdbcDeclarationStore store,
            EffectiveDeclarationService effective,
            DeclarationMigrationAutoEnqueueService migrationAutoEnqueue,
            OperatorTenantAccess tenantAccess) {
        this.store = Objects.requireNonNull(store, "store");
        this.effective = Objects.requireNonNull(effective, "effective");
        this.migrationAutoEnqueue = Objects.requireNonNull(migrationAutoEnqueue, "migrationAutoEnqueue");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
    }

    /** List latest drafts for tenant (+ optional kind filter) — 列出租户最新草稿（可选 kind 过滤）。 */
    @GetMapping(PATH)
    public RevisionsDocument list(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "kind", required = false) String kind) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = requireKind(kind);
        List<RevisionDocument> revisions =
                store.listLatest(tid, k).stream().map(DeclarationDraftEndpoint::document).toList();
        return new RevisionsDocument(revisions);
    }

    /** Latest open draft (404 if none) — 最新未晋升草稿（无则 404）。 */
    @GetMapping(PATH + "/{kind}/{key}")
    public RevisionDocument latest(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        return store.latest(tid, k, key)
                .map(DeclarationDraftEndpoint::document)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Promoted history newest-first — 已晋升历史，新在前。 */
    @GetMapping(PATH + "/{kind}/{key}/revisions")
    public RevisionsDocument history(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        List<RevisionDocument> revisions =
                store.listRevisions(tid, k, key).stream().map(DeclarationDraftEndpoint::document).toList();
        return new RevisionsDocument(revisions);
    }

    /** Effective summary (draft|promoted|classpath) — 生效摘要（草稿|已晋升|classpath）。 */
    @GetMapping(PATH + "/{kind}/{key}/effective")
    public EffectiveDocument effective(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        String source = effective.resolutionSource(tid, k, key);
        boolean fromDraft = EffectiveDeclarationService.SOURCE_DRAFT.equals(source);
        return switch (k) {
            case ENTITY -> effective
                    .effectiveEntity(tid, key)
                    .map(e -> new EffectiveDocument(
                            k.wireName(),
                            e.entityKey(),
                            e.version(),
                            e.fields().stream().map(f -> f.name()).toList(),
                            fromDraft,
                            source))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            case FORM -> {
                RenderedForm form = effective
                        .effectiveForm(tid, key)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
                yield new EffectiveDocument(
                        k.wireName(),
                        form.formKey(),
                        form.version(),
                        form.fields().stream().map(f -> f.name()).toList(),
                        fromDraft,
                        source);
            }
            case FLOW -> {
                RenderedFlow flow = effective
                        .effectiveFlow(tid, key)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
                yield new EffectiveDocument(
                        k.wireName(), flow.flowKey(), flow.version(), List.of(), fromDraft, source);
            }
        };
    }

    /**
     * Save draft YAML; entity path auto-enqueues PENDING migrations (fail-closed validation) -
     * 保存草稿 YAML；实体路径自动入队 PENDING 迁移（校验失败关闭）。
     */
    @PutMapping(PATH + "/{kind}/{key}")
    public RevisionDocument save(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody SaveDraftRequest body) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        if (body == null || body.yamlBody() == null || body.yamlBody().isBlank()) {
            throw new IllegalArgumentException("yamlBody required");
        }
        String yaml = body.yamlBody();
        validateYaml(k, key, yaml);
        // Plan entity DDL before insert so unsafe type/table/PK changes fail closed without a draft row.
        // 先规划实体 DDL，危险类型/表名/主键变更在落库前失败关闭。
        List<String> planned = List.of();
        if (k == DeclarationKind.ENTITY) {
            planned = migrationAutoEnqueue.planForEntityYaml(tid, key, yaml);
        }
        DeclarationRevision saved = store.saveDraft(tid, k, key, yaml, operator.subjectId());
        List<DeclarationMigration> enqueued = migrationAutoEnqueue.enqueuePlanned(saved, planned);
        return document(saved, enqueued);
    }

    private void validateYaml(DeclarationKind kind, String pathKey, String yaml) {
        String key = pathKey == null ? "" : pathKey.trim();
        try {
            switch (kind) {
                case ENTITY -> {
                    RenderedEntity entity = entityRenderer.render(yaml);
                    if (!key.equals(entity.entityKey())) {
                        throw new IllegalArgumentException("entityKey must match path key");
                    }
                    DeclarationDraftConstraints.requireEntity(entity);
                }
                case FORM -> {
                    RenderedForm form = formRenderer.render(yaml);
                    if (!key.equals(form.formKey())) {
                        throw new IllegalArgumentException("formKey must match path key");
                    }
                    DeclarationDraftConstraints.requireForm(form);
                }
                case FLOW -> {
                    RenderedFlow flow = pageRenderer.render(yaml);
                    if (!key.equals(flow.flowKey())) {
                        throw new IllegalArgumentException("flowKey must match path key");
                    }
                    DeclarationDraftConstraints.requireFlow(flow);
                }
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank() ? "invalid yaml" : ex.getMessage();
            throw new IllegalArgumentException(message, ex);
        }
    }

    private String requireTenant(OperatorPrincipal operator, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required");
        }
        String tid = tenantId.trim();
        tenantAccess.requireGranted(operator, tid);
        return tid;
    }

    private static DeclarationKind requireKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind required");
        }
        return DeclarationKind.fromWire(kind);
    }

    private static RevisionDocument document(DeclarationRevision row) {
        return document(row, List.of());
    }

    private static RevisionDocument document(DeclarationRevision row, List<DeclarationMigration> enqueued) {
        List<String> ids = enqueued == null || enqueued.isEmpty()
                ? List.of()
                : enqueued.stream().map(DeclarationMigration::migrationId).toList();
        return new RevisionDocument(
                row.tenantId(),
                row.kind().wireName(),
                row.declarationKey(),
                row.revision(),
                row.yamlBody(),
                row.draftState(),
                row.updatedAt(),
                row.updatedBySubjectId(),
                ids);
    }

    /** RevisionsDocument — 修订列表（每键最新或历史）。 */
    public record RevisionsDocument(List<RevisionDocument> revisions) {}

    /** RevisionDocument — 一次声明修订。 */
    /**
     * {@code enqueuedMigrationIds} — non-empty only on entity draft PUT when RT-4 auto-enqueued
     * PENDING jobs (MigUX-2b). Empty on GET/list.
     */
    public record RevisionDocument(
            String tenantId,
            String declarationKind,
            String declarationKey,
            int revision,
            String yamlBody,
            String draftState,
            Instant updatedAt,
            String updatedBySubjectId,
            List<String> enqueuedMigrationIds) {}

    /** SaveDraftRequest — 保存草稿正文。 */
    public record SaveDraftRequest(String yamlBody) {}

    /**
     * EffectiveDocument — 生效声明摘要：种类、键、版本、字段名、是否来自未晋升草稿、解析来源
     * ({@code draft}|{@code promoted}|{@code classpath})。
     */
    public record EffectiveDocument(
            String declarationKind,
            String key,
            int version,
            List<String> fieldNames,
            boolean fromDraft,
            String source) {}
}
