package com.subjex.platform.app.form;

import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.capability.CapabilityRunner;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.entity.GenericEntityStore;
import com.subjex.platform.app.entity.HybridEntityStore;
import com.subjex.platform.app.form.action.CapabilityInvokeDomainActionHandler;
import com.subjex.platform.app.form.action.ConfigOverrideDomainActionHandler;
import com.subjex.platform.app.form.action.EntityRecordUpsertDomainActionHandler;
import com.subjex.platform.app.form.action.RegistryRegisterDomainActionHandler;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * FormDomainActionRunner — dispatches form {@code domainAction} to registered {@link DomainActionHandler}s.
 * 表单领域动作执行器：将声明的 {@code domainAction} 分发给已注册的 {@link DomainActionHandler}。
 * <p>
 * Fail-closed at construction: every {@link DomainActionKey} must have exactly one handler.
 * 构造时失败关闭：每个 {@link DomainActionKey} 必须恰好有一个处理器。
 */
public final class FormDomainActionRunner {

    private final Map<DomainActionKey, DomainActionHandler> handlers;

    /**
     * Spring / production: index injected handler beans.
     * Spring / 生产：索引注入的处理器 Bean。
     */
    public FormDomainActionRunner(List<DomainActionHandler> handlers) {
        this.handlers = index(handlers);
    }

    /**
     * Test helper: install the built-in handlers without Spring.
     * 测试辅助：无 Spring 时安装内建处理器。
     */
    public FormDomainActionRunner(
            ServiceCatalog services,
            ConfigCatalog config,
            EffectiveDeclarationService effective,
            GenericEntityStore tableEntities,
            HybridEntityStore hybridEntities,
            CapabilityRunner capabilities,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess) {
        this(builtinHandlers(
                services, config, effective, tableEntities, hybridEntities, capabilities, tenantGuard, tenantAccess));
    }

    public String apply(RenderedForm form, Map<String, Object> accepted) {
        return apply(form, accepted, null, null);
    }

    public String apply(RenderedForm form, Map<String, Object> accepted, String tenantHeader) {
        return apply(form, accepted, tenantHeader, null);
    }

    public String apply(
            RenderedForm form, Map<String, Object> accepted, String tenantHeader, OperatorPrincipal operator) {
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(accepted, "accepted");
        DomainActionKey key = form.domainAction();
        DomainActionHandler handler = handlers.get(key);
        if (handler == null) {
            throw new IllegalStateException("no DomainActionHandler for " + key.key());
        }
        return handler.apply(form, accepted, tenantHeader, operator);
    }

    /**
     * Index handlers; reject duplicates and missing enum keys.
     * 建索引；拒绝重复键与缺失的枚举键。
     */
    static Map<DomainActionKey, DomainActionHandler> index(List<DomainActionHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers");
        Map<DomainActionKey, DomainActionHandler> byKey = new EnumMap<>(DomainActionKey.class);
        for (DomainActionHandler handler : handlers) {
            Objects.requireNonNull(handler, "handler");
            DomainActionKey key = Objects.requireNonNull(handler.key(), "handler.key");
            DomainActionHandler previous = byKey.put(key, handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "duplicate DomainActionHandler for " + key.key() + ": "
                                + previous.getClass().getName()
                                + " and "
                                + handler.getClass().getName());
            }
        }
        List<String> missing = new ArrayList<>();
        for (DomainActionKey known : DomainActionKey.values()) {
            if (!byKey.containsKey(known)) {
                missing.add(known.key());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("missing DomainActionHandler for: " + String.join(", ", missing));
        }
        return Map.copyOf(byKey);
    }

    /** Built-in handlers matching production beans — 与生产 Bean 一致的内建处理器。 */
    public static List<DomainActionHandler> builtinHandlers(
            ServiceCatalog services,
            ConfigCatalog config,
            EffectiveDeclarationService effective,
            GenericEntityStore tableEntities,
            HybridEntityStore hybridEntities,
            CapabilityRunner capabilities,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess) {
        List<DomainActionHandler> list = new ArrayList<>();
        list.add(new RegistryRegisterDomainActionHandler(services));
        list.add(new ConfigOverrideDomainActionHandler(config));
        list.add(new EntityRecordUpsertDomainActionHandler(
                effective, tableEntities, hybridEntities, tenantGuard, tenantAccess));
        list.add(new CapabilityInvokeDomainActionHandler(
                DomainActionKey.CAPABILITY_ALGO_HASH_FINGERPRINT, "algo.hashFingerprint", capabilities));
        list.add(new CapabilityInvokeDomainActionHandler(
                DomainActionKey.CAPABILITY_ALGO_NORMALIZE_WHITESPACE,
                "algo.normalizeWhitespace",
                capabilities));
        list.add(new CapabilityInvokeDomainActionHandler(
                DomainActionKey.CAPABILITY_AI_SUMMARIZE_PREVIEW, "ai.summarizePreview", capabilities));
        list.add(new CapabilityInvokeDomainActionHandler(
                DomainActionKey.CAPABILITY_AI_SUGGEST_TITLE_PREVIEW, "ai.suggestTitlePreview", capabilities));
        return List.copyOf(list);
    }
}
