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
 * with EntityRenderer / FormRenderer / PageRenderer before save.
 * 查询参数 {@code tenantId} 必填（空则 400）。操作员须有租户授权。
 * GET 要 {@code declaration.read}；PUT 要 {@code declaration.write}。保存前用对应渲染器校验 YAML。
 */
@RestController
public class DeclarationDraftEndpoint {

    /** JSON base for declaration drafts — 声明草稿 JSON 根路径。 */
    public static final String PATH = JsonApi.BASE + "/declarations";

    private final JdbcDeclarationStore store;
    private final EffectiveDeclarationService effective;
    private final OperatorTenantAccess tenantAccess;
    private final EntityRenderer entityRenderer = new EntityRenderer();
    private final FormRenderer formRenderer = new FormRenderer();
    private final PageRenderer pageRenderer = new PageRenderer();

    public DeclarationDraftEndpoint(
            JdbcDeclarationStore store, EffectiveDeclarationService effective, OperatorTenantAccess tenantAccess) {
        this.store = Objects.requireNonNull(store, "store");
        this.effective = Objects.requireNonNull(effective, "effective");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
    }

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

    @GetMapping(PATH + "/{kind}/{key}/effective")
    public EffectiveDocument effective(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("kind") String kind,
            @PathVariable("key") String key,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenant(operator, tenantId);
        DeclarationKind k = DeclarationKind.fromWire(kind);
        return switch (k) {
            case ENTITY -> effective
                    .effectiveEntity(tid, key)
                    .map(e -> new EffectiveDocument(
                            k.wireName(),
                            e.entityKey(),
                            e.version(),
                            e.fields().stream().map(f -> f.name()).toList(),
                            effective.hasEntityDraft(tid, key)))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            case FORM -> {
                RenderedForm form = effective
                        .effectiveForm(tid, key)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
                boolean fromDraft = store.latest(tid, DeclarationKind.FORM, key).isPresent();
                yield new EffectiveDocument(
                        k.wireName(),
                        form.formKey(),
                        form.version(),
                        form.fields().stream().map(f -> f.name()).toList(),
                        fromDraft);
            }
            case FLOW -> {
                RenderedFlow flow = effective
                        .effectiveFlow(tid, key)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
                boolean fromDraft = store.latest(tid, DeclarationKind.FLOW, key).isPresent();
                yield new EffectiveDocument(k.wireName(), flow.flowKey(), flow.version(), List.of(), fromDraft);
            }
        };
    }

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
        DeclarationRevision saved = store.saveDraft(tid, k, key, yaml, operator.subjectId());
        return document(saved);
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
                }
                case FORM -> {
                    RenderedForm form = formRenderer.render(yaml);
                    if (!key.equals(form.formKey())) {
                        throw new IllegalArgumentException("formKey must match path key");
                    }
                }
                case FLOW -> {
                    RenderedFlow flow = pageRenderer.render(yaml);
                    if (!key.equals(flow.flowKey())) {
                        throw new IllegalArgumentException("flowKey must match path key");
                    }
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
        return new RevisionDocument(
                row.tenantId(),
                row.kind().wireName(),
                row.declarationKey(),
                row.revision(),
                row.yamlBody(),
                row.draftState(),
                row.updatedAt(),
                row.updatedBySubjectId());
    }

    /** RevisionsDocument — 修订列表（每键最新或历史）。 */
    public record RevisionsDocument(List<RevisionDocument> revisions) {}

    /** RevisionDocument — 一次声明修订。 */
    public record RevisionDocument(
            String tenantId,
            String declarationKind,
            String declarationKey,
            int revision,
            String yamlBody,
            String draftState,
            Instant updatedAt,
            String updatedBySubjectId) {}

    /** SaveDraftRequest — 保存草稿正文。 */
    public record SaveDraftRequest(String yamlBody) {}

    /**
     * EffectiveDocument — 生效声明摘要：种类、键、版本、字段名、是否来自草稿。
     */
    public record EffectiveDocument(
            String declarationKind, String key, int version, List<String> fieldNames, boolean fromDraft) {}
}
