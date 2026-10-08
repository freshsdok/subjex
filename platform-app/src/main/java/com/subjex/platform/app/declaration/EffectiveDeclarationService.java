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

/**
 * EffectiveDeclarationService — 生效声明：租户最新 DRAFT 覆盖 classpath 目录。
 * <p>
 * Load order: DB latest draft for (tenant, kind, key) wins; otherwise classpath catalog.
 * Callers opt in via this service / {@code /effective}, or via runtime helpers when
 * {@link TenantDeclarationContext#overlayRequested(String)} (non-blank {@code X-Tenant-Id}).
 * JDBC entity paths use {@link #runtimeEntity(String, String)} (safe overlay: classpath must
 * exist and tableName + PK must match; otherwise 409 / missing).
 * 加载顺序：库内最新草稿优先，否则 classpath。调用方经本服务、{@code /effective}，或在带租户头时
 * 经运行时解析选用。实体 JDBC 路径用 {@link #runtimeEntity}（安全覆盖：须有 classpath 且表名+主键一致）。
 */
public final class EffectiveDeclarationService {

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
     * Effective entity: draft YAML if present, else classpath — 生效实体：有草稿用草稿，否则 classpath。
     */
    public Optional<RenderedEntity> effectiveEntity(String tenantId, String entityKey) {
        String key = requireKey(entityKey);
        Optional<DeclarationRevision> draft = store.latest(tenantId, DeclarationKind.ENTITY, key);
        if (draft.isPresent()) {
            return Optional.of(entityRenderer.render(draft.get().yamlBody()));
        }
        return entityCatalog.find(key);
    }

    /**
     * Effective form: draft YAML if present, else classpath — 生效表单：有草稿用草稿，否则 classpath。
     */
    public Optional<RenderedForm> effectiveForm(String tenantId, String formKey) {
        String key = requireKey(formKey);
        Optional<DeclarationRevision> draft = store.latest(tenantId, DeclarationKind.FORM, key);
        if (draft.isPresent()) {
            return Optional.of(formRenderer.render(draft.get().yamlBody()));
        }
        return formCatalog.find(key);
    }

    /**
     * Effective flow: draft YAML if present, else classpath — 生效流程：有草稿用草稿，否则 classpath。
     */
    public Optional<RenderedFlow> effectiveFlow(String tenantId, String flowKey) {
        String key = requireKey(flowKey);
        Optional<DeclarationRevision> draft = store.latest(tenantId, DeclarationKind.FLOW, key);
        if (draft.isPresent()) {
            return Optional.of(pageRenderer.render(draft.get().yamlBody()));
        }
        return pageCatalog.find(key);
    }

    /**
     * Entity metadata for JDBC / generic CRUD — 供 JDBC / 通用 CRUD 用的实体元数据。
     * <p>
     * No tenant header → classpath only. With tenant + draft: overlay only when a classpath
     * entity exists and {@code tableName} + primary-key name match; otherwise
     * {@link DeclarationOverlayConflict} (409). Draft-only keys (no classpath) are not overlaid
     * on the JDBC path (empty → 404).
     * 无租户头 → 仅 classpath。有租户且有草稿：仅当 classpath 存在且表名+主键名一致时覆盖；
     * 否则 {@link DeclarationOverlayConflict}（409）。仅草稿无 classpath 的键不在 JDBC 路径覆盖（空 → 404）。
     */
    public Optional<RenderedEntity> runtimeEntity(String tenantHeader, String entityKey) {
        String key = requireKey(entityKey);
        if (!TenantDeclarationContext.overlayRequested(tenantHeader)) {
            return entityCatalog.find(key);
        }
        String tenant = tenantHeader.trim();
        Optional<RenderedEntity> classpath = entityCatalog.find(key);
        Optional<DeclarationRevision> draft = store.latest(tenant, DeclarationKind.ENTITY, key);
        if (draft.isEmpty()) {
            return classpath;
        }
        if (classpath.isEmpty()) {
            return Optional.empty();
        }
        RenderedEntity overlaid = entityRenderer.render(draft.get().yamlBody());
        RenderedEntity base = classpath.get();
        if (!Objects.equals(base.tableName(), overlaid.tableName())
                || !Objects.equals(base.primaryKey().name(), overlaid.primaryKey().name())) {
            throw new DeclarationOverlayConflict(
                    "entity draft changes tableName or primary key; refuse runtime overlay until migration/promote");
        }
        return Optional.of(overlaid);
    }

    /** Whether the latest entity draft exists (overlay would win) — 是否存在会覆盖 classpath 的实体草稿。 */
    public boolean hasEntityDraft(String tenantId, String entityKey) {
        return store.latest(tenantId, DeclarationKind.ENTITY, requireKey(entityKey)).isPresent();
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("declarationKey required");
        }
        return key.trim();
    }
}
