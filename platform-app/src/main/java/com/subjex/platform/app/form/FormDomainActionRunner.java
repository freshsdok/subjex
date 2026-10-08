package com.subjex.platform.app.form;

import com.subjex.entity.declare.EntityField;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.capability.CapabilityRunner;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.entity.GenericEntityStore;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * FormDomainActionRunner — 表单领域动作执行器：按声明的 {@code domainAction} 调用已有目录 / 实体存储，不再按 formKey 分支。
 * <p>
 * Allowed keys come from the checked-in catalog. New forms that reuse an existing key need no Java change;
 * a new key still needs one enum + switch arm. {@code entity.record.upsert} writes via {@link GenericEntityStore}
 * using the form's {@code entityKey} (new entity → YAML only), including {@code service-note} on Flyway V11.
 * With a tenant header, upsert resolves entity metadata via {@link EffectiveDeclarationService#runtimeEntity}
 * (safe overlay). Capability actions invoke {@link CapabilityRunner} (algorithm deterministic; AI preview stub
 * does not write).
 * 允许的键来自检入目录。复用已有键的新表单不必改 Java；新增键仍需枚举与分支。
 * {@code entity.record.upsert} 经 {@link GenericEntityStore} 按表单 {@code entityKey} 写入（含 service-note / V11）；
 * 带租户头时走安全覆盖。能力动作经 {@link CapabilityRunner}（算法确定性；AI 预览桩不写库）。
 */
public final class FormDomainActionRunner {

    private final ServiceCatalog services;
    private final ConfigCatalog config;
    private final EffectiveDeclarationService effective;
    private final GenericEntityStore genericEntities;
    private final CapabilityRunner capabilities;

    public FormDomainActionRunner(
            ServiceCatalog services,
            ConfigCatalog config,
            EffectiveDeclarationService effective,
            GenericEntityStore genericEntities,
            CapabilityRunner capabilities) {
        this.services = Objects.requireNonNull(services, "services");
        this.config = Objects.requireNonNull(config, "config");
        this.effective = Objects.requireNonNull(effective, "effective");
        this.genericEntities = Objects.requireNonNull(genericEntities, "genericEntities");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    /**
     * Apply the form's declared domain action (no tenant overlay) — 执行表单声明的领域动作（无租户覆盖）。
     */
    public String apply(RenderedForm form, Map<String, Object> accepted) {
        return apply(form, accepted, null);
    }

    /**
     * Apply the form's declared domain action — 执行表单声明的领域动作。
     *
     * @param tenantHeader optional {@code X-Tenant-Id} for entity.record.upsert overlay
     * @return short result summary for submission history / 提交历史用的短摘要
     */
    public String apply(RenderedForm form, Map<String, Object> accepted, String tenantHeader) {
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(accepted, "accepted");
        DomainActionKey key = form.domainAction();
        return switch (key) {
            case REGISTRY_REGISTER -> registerService(accepted);
            case CONFIG_OVERRIDE -> overrideConfig(accepted);
            case ENTITY_RECORD_UPSERT -> upsertEntityRecord(form, accepted, tenantHeader);
            case CAPABILITY_ALGO_HASH_FINGERPRINT ->
                    capabilities.run("algo.hashFingerprint", accepted);
            case CAPABILITY_AI_SUMMARIZE_PREVIEW ->
                    capabilities.run("ai.summarizePreview", accepted);
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
        config.override(configKey, configValue);
        return configKey + "=" + configValue;
    }

    private String upsertEntityRecord(RenderedForm form, Map<String, Object> accepted, String tenantHeader) {
        String entityKey = form.entityKey();
        if (entityKey == null || entityKey.isBlank()) {
            throw new IllegalArgumentException("entityKey is required for entity.record.upsert");
        }
        // Grant is enforced by FormSubmissionEndpoint when tenantScoped / overlay header present.
        RenderedEntity entity = effective
                .runtimeEntity(tenantHeader, entityKey)
                .orElseThrow(() -> new IllegalArgumentException("unknown entity: " + entityKey));
        if (entity.tenantScoped()) {
            throw new IllegalArgumentException(
                    "tenantScoped entities are not supported for entity.record.upsert yet");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (EntityField field : entity.fields()) {
            if (accepted.containsKey(field.name())) {
                values.put(field.name(), accepted.get(field.name()));
            }
        }
        String pkName = entity.primaryKey().name();
        genericEntities.save(entity, values);
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
