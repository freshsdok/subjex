package com.subjex.platform.app.form.action;

import com.subjex.entity.declare.EntityField;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.entity.GenericEntityStore;
import com.subjex.platform.app.entity.HybridEntityStore;
import com.subjex.platform.app.form.DomainActionHandler;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.DeclarationAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * entity.record.upsert — upsert by form entityKey; routes table vs hybrid/document stores.
 * entity.record.upsert — 按表单 entityKey 写入；按 storageMode 路由 table / hybrid·document。
 */
@Component
public final class EntityRecordUpsertDomainActionHandler implements DomainActionHandler {

    private final EffectiveDeclarationService effective;
    private final GenericEntityStore tableEntities;
    private final HybridEntityStore hybridEntities;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public EntityRecordUpsertDomainActionHandler(
            EffectiveDeclarationService effective,
            GenericEntityStore tableEntities,
            HybridEntityStore hybridEntities,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess) {
        this.effective = Objects.requireNonNull(effective, "effective");
        this.tableEntities = Objects.requireNonNull(tableEntities, "tableEntities");
        this.hybridEntities = Objects.requireNonNull(hybridEntities, "hybridEntities");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
    }

    @Override
    public DomainActionKey key() {
        return DomainActionKey.ENTITY_RECORD_UPSERT;
    }

    @Override
    public String apply(
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
        if (entity.sharedRecordStorage()) {
            hybridEntities.save(entity, values, storeTenant);
        } else {
            tableEntities.save(entity, values, storeTenant);
        }
        return entityKey + ":" + Objects.toString(values.get(pkName), "");
    }
}
