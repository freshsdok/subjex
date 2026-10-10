package com.subjex.platform.app.form;

import com.subjex.entity.declare.EntityField;
import com.subjex.entity.declare.EntityStorageMode;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.capability.CapabilityRunner;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.contract.config.ConfigNamespaces;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.entity.GenericEntityStore;
import com.subjex.platform.app.entity.HybridEntityStore;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.DeclarationAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * FormDomainActionRunner — 表单领域动作执行器：按声明的 {@code domainAction} 调用已有目录 / 实体存储。
 * <p>
 * {@code entity.record.upsert} routes by {@code storageMode}: table → {@link GenericEntityStore},
 * hybrid → {@link HybridEntityStore} (ES-3 / ADR 0002).
 * {@code entity.record.upsert} 按 storageMode 路由（ES-3）：table → GenericEntityStore，hybrid → HybridEntityStore。
 */
public final class FormDomainActionRunner {

    private final ServiceCatalog services;
    private final ConfigCatalog config;
    private final EffectiveDeclarationService effective;
    private final GenericEntityStore tableEntities;
    private final HybridEntityStore hybridEntities;
    private final CapabilityRunner capabilities;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public FormDomainActionRunner(
            ServiceCatalog services,
            ConfigCatalog config,
            EffectiveDeclarationService effective,
            GenericEntityStore tableEntities,
            HybridEntityStore hybridEntities,
            CapabilityRunner capabilities,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess) {
        this.services = Objects.requireNonNull(services, "services");
        this.config = Objects.requireNonNull(config, "config");
        this.effective = Objects.requireNonNull(effective, "effective");
        this.tableEntities = Objects.requireNonNull(tableEntities, "tableEntities");
        this.hybridEntities = Objects.requireNonNull(hybridEntities, "hybridEntities");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
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
        return switch (key) {
            case REGISTRY_REGISTER -> registerService(accepted);
            case CONFIG_OVERRIDE -> overrideConfig(accepted);
            case ENTITY_RECORD_UPSERT -> upsertEntityRecord(form, accepted, tenantHeader, operator);
            case CAPABILITY_ALGO_HASH_FINGERPRINT ->
                    capabilities.run("algo.hashFingerprint", accepted);
            case CAPABILITY_ALGO_NORMALIZE_WHITESPACE ->
                    capabilities.run("algo.normalizeWhitespace", accepted);
            case CAPABILITY_AI_SUMMARIZE_PREVIEW ->
                    capabilities.run("ai.summarizePreview", accepted);
            case CAPABILITY_AI_SUGGEST_TITLE_PREVIEW ->
                    capabilities.run("ai.suggestTitlePreview", accepted);
        };
    }

    private String registerService(Map<String, Object> accepted) {
        String serviceName = stringValue(accepted, "serviceName");
        String host = stringValue(accepted, "host");
        int port = intValue(accepted, "port");
        services.register(new ServiceEndpoint(serviceName, host, port));
        return serviceName + "@" + host + ":" + port;
    }

    private String overrideConfig(Map<String, Object> accepted) {
        String configKey = stringValue(accepted, "configKey");
        String configValue = stringValue(accepted, "configValue");
        config.put(ConfigNamespaces.DEFAULT, configKey, configValue);
        return configKey + "=" + configValue;
    }

    private String upsertEntityRecord(
            RenderedForm form, Map<String, Object> accepted, String tenantHeader, OperatorPrincipal operator) {
        String entityKey = form.entityKey();
        if (entityKey == null || entityKey.isBlank()) {
            throw new IllegalArgumentException("entityKey is required for entity.record.upsert");
        }
        RenderedEntity entity = effective
                .runtimeEntity(tenantHeader, entityKey)
                .orElseThrow(() -> new IllegalArgumentException("unknown entity: " + entityKey));
        DeclarationAccess.requireTenantWhenScoped(
                tenantGuard,
                tenantAccess,
                operator,
                entity.tenantScoped(),
                tenantHeader,
                AccessResource.of("entity", entityKey),
                AccessAction.of("upsert"),
                form.permission());
        Map<String, Object> values = new LinkedHashMap<>();
        for (EntityField field : entity.fields()) {
            if (accepted.containsKey(field.name())) {
                values.put(field.name(), accepted.get(field.name()));
            }
        }
        String pkName = entity.primaryKey().name();
        String storeTenant = entity.tenantScoped() ? tenantHeader : null;
        if (entity.storageMode() == EntityStorageMode.HYBRID) {
            hybridEntities.save(entity, values, storeTenant);
        } else {
            tableEntities.save(entity, values, storeTenant);
        }
        return entityKey + ":" + Objects.toString(values.get(pkName), "");
    }

    private static String stringValue(Map<String, Object> values, String name) {
        return Objects.toString(values.get(name), "");
    }

    private static int intValue(Map<String, Object> values, String name) {
        Object raw = values.get(name);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("field " + name + " must be a whole number");
    }
}
