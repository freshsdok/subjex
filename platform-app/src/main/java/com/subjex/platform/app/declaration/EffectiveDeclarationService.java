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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * EffectiveDeclarationService — 生效声明：租户 DRAFT / PROMOTED 覆盖 classpath。
 * <p>
 * Load order when a tenant is in play (explicit hot-reload):
 * <ol>
 *   <li>open {@code DRAFT} ({@link JdbcDeclarationStore#latestDraft})</li>
 *   <li>else latest {@code PROMOTED} ({@link JdbcDeclarationStore#latestPromoted}) — hot-reload path</li>
 *   <li>else classpath catalog</li>
 * </ol>
 * Callers opt in via this service / {@code /effective}, or via runtime helpers when
 * {@link TenantDeclarationContext#overlayRequested(String)} (non-blank {@code X-Tenant-Id}).
 * JDBC entity paths use {@link #runtimeEntity(String, String)} (safe overlay: classpath must
 * exist and tableName + PK must match; otherwise 409 / missing).
 * 加载顺序（有租户时）：未晋升草稿 → 已晋升（热加载）→ classpath。调用方经本服务、{@code /effective}，
 * 或在带租户头时经运行时解析选用。实体 JDBC 路径用 {@link #runtimeEntity}（安全覆盖：须有 classpath
 * 且表名+主键一致）。
 */
public final class EffectiveDeclarationService {

    /** Resolution source wire values — 解析来源线值。 */
    public static final String SOURCE_DRAFT = "draft";

    public static final String SOURCE_PROMOTED = "promoted";

    public static final String SOURCE_CLASSPATH = "classpath";

    private final JdbcDeclarationStore store;
    private final EntityCatalog entityCatalog;
    private final FormCatalog formCatalog;
    private final PageCatalog pageCatalog;
    private final EntityRenderer entityRenderer;
    private final FormRenderer formRenderer;
    private final PageRenderer pageRenderer;

    public EffectiveDeclarationService(
            JdbcDeclarationStore store, EntityCatalog entityCatalog, FormCatalog formCatalog, PageCatalog pageCatalog) {
        this(
                store,
                entityCatalog,
                formCatalog,
                pageCatalog,
                new EntityRenderer(),
                new FormRenderer(),
                new PageRenderer());
    }

    EffectiveDeclarationService(
            JdbcDeclarationStore store,
            EntityCatalog entityCatalog,
            FormCatalog formCatalog,
            PageCatalog pageCatalog,
            EntityRenderer entityRenderer,
            FormRenderer formRenderer,
            PageRenderer pageRenderer) {
        this.store = Objects.requireNonNull(store, "store");
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
     * Entity metadata for JDBC / generic CRUD — 供 JDBC / 通用 CRUD 用的实体元数据。
     * <p>
     * No tenant header → classpath only. With tenant + DRAFT or PROMOTED overlay: only when a
     * classpath entity exists and {@code tableName} + primary-key name match; otherwise
     * {@link DeclarationOverlayConflict} (409). Draft/promoted-only keys (no classpath) are not
     * overlaid on the JDBC path (empty → 404).
     * 无租户头 → 仅 classpath。有租户且有 DRAFT/PROMOTED：仅当 classpath 存在且表名+主键名一致时覆盖；
     * 否则 {@link DeclarationOverlayConflict}（409）。仅库内无 classpath 的键不在 JDBC 路径覆盖（空 → 404）。
     */
    public Optional<RenderedEntity> runtimeEntity(String tenantHeader, String entityKey) {
        String key = requireKey(entityKey);
        if (!TenantDeclarationContext.overlayRequested(tenantHeader)) {
            return entityCatalog.find(key);
        }
        String tenant = tenantHeader.trim();
        Optional<RenderedEntity> classpath = entityCatalog.find(key);
        Optional<DeclarationRevision> overlay = store.latestDraft(tenant, DeclarationKind.ENTITY, key);
        if (overlay.isEmpty()) {
            overlay = store.latestPromoted(tenant, DeclarationKind.ENTITY, key);
        }
        if (overlay.isEmpty()) {
            return classpath;
        }
        if (classpath.isEmpty()) {
            return Optional.empty();
        }
        RenderedEntity overlaid = entityRenderer.render(overlay.get().yamlBody());
        RenderedEntity base = classpath.get();
        if (!Objects.equals(base.tableName(), overlaid.tableName())
                || !Objects.equals(base.primaryKey().name(), overlaid.primaryKey().name())) {
            throw new DeclarationOverlayConflict(
                    "entity draft/promoted changes tableName or primary key; refuse runtime overlay until migration/promote");
        }
        return Optional.of(overlaid);
    }

    /**
     * Whether an open entity DRAFT exists (not PROMOTED) — 是否存在未晋升的实体草稿（不含已晋升）。
     */
    public boolean hasEntityDraft(String tenantId, String entityKey) {
        return store.latestDraft(tenantId, DeclarationKind.ENTITY, requireKey(entityKey)).isPresent();
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
