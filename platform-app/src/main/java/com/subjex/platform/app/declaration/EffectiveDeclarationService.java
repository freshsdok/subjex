package com.subjex.platform.app.declaration;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.EntityRenderer;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.FormRenderer;
import com.subjex.form.render.RenderedForm;
import com.subjex.page.declare.PageRenderer;
import com.subjex.page.declare.RenderedFlow;
import com.subjex.platform.app.form.FormCatalog;
import com.subjex.platform.app.page.PageCatalog;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * EffectiveDeclarationService — 生效声明：租户 DRAFT / PROMOTED 覆盖 classpath。
 * <p>
 * Load order when a tenant is in play (explicit hot-reload / {@code /effective}):
 * <ol>
 *   <li>open {@code DRAFT} ({@link JdbcDeclarationStore#latestDraft})</li>
 *   <li>else latest {@code PROMOTED} ({@link JdbcDeclarationStore#latestPromoted}) — hot-reload path</li>
 *   <li>else classpath catalog</li>
 * </ol>
 * Callers opt in via this service / {@code /effective}, or via runtime helpers when
 * {@link TenantDeclarationContext#overlayRequested(String)} (non-blank {@code X-Tenant-Id}).
 * <p>
 * Pages: {@link #effectiveRuntimePages} / {@link #effectiveFlow} — no write-side page materialization;
 * FLOW promote runs {@link DeclarationRuntimePages#ensureAfterFlowPromote(String)} for path/block contract.
 * <p>
 * JDBC entity paths use {@link #runtimeEntity(String, String)}:
 * <ul>
 *   <li>Classpath samples: DRAFT &gt; PROMOTED overlay only when {@code tableName} + PK match (else 409).</li>
 *   <li>No-classpath (tenant-only) entities: {@code PROMOTED} only, and schema migration for that
 *       revision must be settled with at least one {@code APPLIED} row (open PENDING/REVIEWED/FAILED →
 *       409; no APPLIED → empty / 404). DRAFT must not drive JDBC for brand-new tables.</li>
 * </ul>
 * 加载顺序（有租户时）：未晋升草稿 → 已晋升（热加载）→ classpath。
 * 实体 JDBC：classpath 样例仍可草稿覆盖（表名+主键一致）；无 classpath 的新实体仅 PROMOTED 且迁移已 APPLIED。
 */
public final class EffectiveDeclarationService {

    /** Resolution source wire values — 解析来源线值。 */
    public static final String SOURCE_DRAFT = "draft";

    public static final String SOURCE_PROMOTED = "promoted";

    public static final String SOURCE_CLASSPATH = "classpath";

    private final JdbcDeclarationStore store;
    private final JdbcDeclarationMigrationStore migrationStore;
    private final EntityCatalog entityCatalog;
    private final FormCatalog formCatalog;
    private final PageCatalog pageCatalog;
    private final EntityRenderer entityRenderer;
    private final FormRenderer formRenderer;
    private final PageRenderer pageRenderer;

    public EffectiveDeclarationService(
            JdbcDeclarationStore store,
            JdbcDeclarationMigrationStore migrationStore,
            EntityCatalog entityCatalog,
            FormCatalog formCatalog,
            PageCatalog pageCatalog) {
        this(
                store,
                migrationStore,
                entityCatalog,
                formCatalog,
                pageCatalog,
                new EntityRenderer(),
                new FormRenderer(),
                new PageRenderer());
    }

    EffectiveDeclarationService(
            JdbcDeclarationStore store,
            JdbcDeclarationMigrationStore migrationStore,
            EntityCatalog entityCatalog,
            FormCatalog formCatalog,
            PageCatalog pageCatalog,
            EntityRenderer entityRenderer,
            FormRenderer formRenderer,
            PageRenderer pageRenderer) {
        this.store = Objects.requireNonNull(store, "store");
        this.migrationStore = Objects.requireNonNull(migrationStore, "migrationStore");
        this.entityCatalog = Objects.requireNonNull(entityCatalog, "entityCatalog");
        this.formCatalog = Objects.requireNonNull(formCatalog, "formCatalog");
        this.pageCatalog = Objects.requireNonNull(pageCatalog, "pageCatalog");
        this.entityRenderer = Objects.requireNonNull(entityRenderer, "entityRenderer");
        this.formRenderer = Objects.requireNonNull(formRenderer, "formRenderer");
        this.pageRenderer = Objects.requireNonNull(pageRenderer, "pageRenderer");
    }

    /**
     * Where the effective declaration would resolve from for a tenant —
     * 某租户下生效声明会解析自何处：{@code draft} | {@code promoted} | {@code classpath}。
     */
    public String resolutionSource(String tenantId, DeclarationKind kind, String declarationKey) {
        String key = requireKey(declarationKey);
        DeclarationKind k = Objects.requireNonNull(kind, "kind");
        if (store.latestDraft(tenantId, k, key).isPresent()) {
            return SOURCE_DRAFT;
        }
        if (store.latestPromoted(tenantId, k, key).isPresent()) {
            return SOURCE_PROMOTED;
        }
        return SOURCE_CLASSPATH;
    }

    /**
     * Effective entity: open DRAFT, else PROMOTED, else classpath —
     * 生效实体：未晋升草稿 → 已晋升 → classpath。
     */
    public Optional<RenderedEntity> effectiveEntity(String tenantId, String entityKey) {
        return resolveOverlay(tenantId, DeclarationKind.ENTITY, entityKey, entityRenderer::render, entityCatalog::find);
    }

    /**
     * Effective form: open DRAFT, else PROMOTED, else classpath —
     * 生效表单：未晋升草稿 → 已晋升 → classpath。
     */
    public Optional<RenderedForm> effectiveForm(String tenantId, String formKey) {
        return resolveOverlay(tenantId, DeclarationKind.FORM, formKey, formRenderer::render, formCatalog::find);
    }

    /**
     * Effective flow: open DRAFT, else PROMOTED, else classpath —
     * 生效流程：未晋升草稿 → 已晋升 → classpath。
     */
    public Optional<RenderedFlow> effectiveFlow(String tenantId, String flowKey) {
        return resolveOverlay(tenantId, DeclarationKind.FLOW, flowKey, pageRenderer::render, pageCatalog::find);
    }

    /**
     * Runtime page paths from the effective flow (RT-5; no separate page YAML) —
     * 从生效流程得到运行时页面路径（RT-5；无独立 page YAML）。Empty when no flow resolves.
     */
    public Optional<DeclarationRuntimePages.Binding> effectiveRuntimePages(String tenantId, String flowKey) {
        return effectiveFlow(tenantId, flowKey).map(DeclarationRuntimePages::fromFlow);
    }

    /**
     * Declaration keys present in the tenant store (latest revision per key) —
     * 租户库内声明键（每键最新修订），供目录索引与 classpath 合并。
     */
    public List<String> tenantDeclarationKeys(String tenantId, DeclarationKind kind) {
        return store.listLatest(tenantId, Objects.requireNonNull(kind, "kind")).stream()
                .map(DeclarationRevision::declarationKey)
                .toList();
    }

    /**
     * Entity metadata for JDBC / generic CRUD — 供 JDBC / 通用 CRUD 用的实体元数据。
     * <p>
     * No tenant header → classpath only. With tenant:
     * <ul>
     *   <li>Classpath entity: DRAFT else PROMOTED overlay when tableName + PK match; else
     *       {@link DeclarationOverlayConflict} (409). Missing overlay → classpath.</li>
     *   <li>No classpath: only latest {@code PROMOTED}; migrations for that revision must all be
     *       {@code APPLIED} or {@code CANCELLED}, and at least one {@code APPLIED} (table exists).
     *       Open migrations → {@link DeclarationPromoteBlockedByMigration} (409). DRAFT-only or no
     *       APPLIED → empty (404).</li>
     * </ul>
     */
    public Optional<RenderedEntity> runtimeEntity(String tenantHeader, String entityKey) {
        String key = requireKey(entityKey);
        if (!TenantDeclarationContext.overlayRequested(tenantHeader)) {
            return entityCatalog.find(key);
        }
        String tenant = tenantHeader.trim();
        Optional<RenderedEntity> classpath = entityCatalog.find(key);
        if (classpath.isPresent()) {
            return runtimeOverlayClasspath(tenant, key, classpath.get());
        }
        return runtimePromotedNoClasspath(tenant, key);
    }

    /**
     * Whether an open entity DRAFT exists (not PROMOTED) — 是否存在未晋升的实体草稿（不含已晋升）。
     */
    public boolean hasEntityDraft(String tenantId, String entityKey) {
        return store.latestDraft(tenantId, DeclarationKind.ENTITY, requireKey(entityKey)).isPresent();
    }

    private Optional<RenderedEntity> runtimeOverlayClasspath(
            String tenant, String key, RenderedEntity base) {
        Optional<DeclarationRevision> overlay = store.latestDraft(tenant, DeclarationKind.ENTITY, key);
        if (overlay.isEmpty()) {
            overlay = store.latestPromoted(tenant, DeclarationKind.ENTITY, key);
        }
        if (overlay.isEmpty()) {
            return Optional.of(base);
        }
        RenderedEntity overlaid = entityRenderer.render(overlay.get().yamlBody());
        if (!Objects.equals(base.tableName(), overlaid.tableName())
                || !Objects.equals(base.primaryKey().name(), overlaid.primaryKey().name())) {
            throw new DeclarationOverlayConflict(
                    "entity draft/promoted changes tableName or primary key; refuse runtime overlay until migration/promote");
        }
        return Optional.of(overlaid);
    }

    private Optional<RenderedEntity> runtimePromotedNoClasspath(String tenant, String key) {
        Optional<DeclarationRevision> promoted = store.latestPromoted(tenant, DeclarationKind.ENTITY, key);
        if (promoted.isEmpty()) {
            // DRAFT-only or missing — never drive JDBC for brand-new tables from DRAFT.
            // 仅草稿或缺失 — 新表禁止用 DRAFT 驱动 JDBC。
            return Optional.empty();
        }
        requirePromotedSchemaReady(tenant, key, promoted.get().revision());
        return Optional.of(entityRenderer.render(promoted.get().yamlBody()));
    }

    /**
     * Same settle rule as promote bind, plus require at least one APPLIED (table present) —
     * 与晋升绑定相同的结清规则，并要求至少一条 APPLIED（表已存在）。
     */
    private void requirePromotedSchemaReady(String tenant, String key, int revision) {
        List<DeclarationMigration> rows =
                migrationStore.listForRevision(tenant, DeclarationKind.ENTITY, key, revision);
        List<DeclarationMigration> blockers = rows.stream()
                .filter(m -> !JdbcDeclarationMigrationStore.APPLIED.equals(m.status())
                        && !JdbcDeclarationMigrationStore.CANCELLED.equals(m.status()))
                .toList();
        if (!blockers.isEmpty()) {
            String statuses = blockers.stream()
                    .map(m -> m.migrationId() + "=" + m.status())
                    .collect(Collectors.joining(", "));
            throw new DeclarationPromoteBlockedByMigration(
                    "runtime entity blocked until migrations for revision "
                            + revision
                            + " are APPLIED or CANCELLED ("
                            + statuses
                            + "); apply schema before JDBC for no-classpath entities");
        }
        boolean anyApplied = rows.stream()
                .anyMatch(m -> JdbcDeclarationMigrationStore.APPLIED.equals(m.status()));
        if (!anyApplied) {
            throw new DeclarationPromoteBlockedByMigration(
                    "runtime entity requires at least one APPLIED migration for revision "
                            + revision
                            + " (no-classpath table); enqueue → review → apply before JDBC");
        }
    }

    private <T> Optional<T> resolveOverlay(
            String tenantId,
            DeclarationKind kind,
            String declarationKey,
            Function<String, T> renderYaml,
            Function<String, Optional<T>> classpathFind) {
        String key = requireKey(declarationKey);
        Optional<DeclarationRevision> draft = store.latestDraft(tenantId, kind, key);
        if (draft.isPresent()) {
            return Optional.of(renderYaml.apply(draft.get().yamlBody()));
        }
        Optional<DeclarationRevision> promoted = store.latestPromoted(tenantId, kind, key);
        if (promoted.isPresent()) {
            return Optional.of(renderYaml.apply(promoted.get().yamlBody()));
        }
        return classpathFind.apply(key);
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("declarationKey required");
        }
        return key.trim();
    }
}
