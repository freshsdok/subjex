package com.subjex.platform.app.form.action;

import com.subjex.entity.declare.EntityField;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.declaration.DeclarationKind;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.entity.GenericEntityStore;
import com.subjex.platform.app.entity.HybridEntityStore;
import com.subjex.platform.app.form.DomainActionHandler;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.DeclarationAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.workflow.RepairTicketWorkflowDefinitions;
import com.subjex.platform.app.workflow.WorkflowService;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * entity.record.upsert — upsert by form entityKey; routes table vs hybrid/document stores.
 * entity.record.upsert — 按表单 entityKey 写入；按 storageMode 路由 table / hybrid·document。
 * <p>
 * WF-RT-2: when {@code entityKey} is {@code repair-ticket}, after a successful save starts a
 * workflow instance (idempotent). Upsert + start run in one {@link TransactionTemplate} when
 * provided so a start failure rolls back the row (fail-closed, no silent orphan). Without a TX
 * template (unit tests), start errors still propagate after save.
 * WF-RT-2：entityKey 为 repair-ticket 时，保存成功后幂等启动工作流；有事务模板则同事务失败关闭。
 */
@Component
public final class EntityRecordUpsertDomainActionHandler implements DomainActionHandler {

    private final EffectiveDeclarationService effective;
    private final GenericEntityStore tableEntities;
    private final HybridEntityStore hybridEntities;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;
    private final WorkflowService workflows;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public EntityRecordUpsertDomainActionHandler(
            EffectiveDeclarationService effective,
            GenericEntityStore tableEntities,
            HybridEntityStore hybridEntities,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            WorkflowService workflows,
            TransactionTemplate transactionTemplate) {
        this.effective = Objects.requireNonNull(effective, "effective");
        this.tableEntities = Objects.requireNonNull(tableEntities, "tableEntities");
        this.hybridEntities = Objects.requireNonNull(hybridEntities, "hybridEntities");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    }

    /**
     * Test helper without workflow auto-start — 无自动起实例的测试构造。
     */
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
        this.workflows = null;
        this.transactionTemplate = null;
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
        if (transactionTemplate != null && isRepairTicket(entityKey)) {
            transactionTemplate.executeWithoutResult(status -> saveAndMaybeStart(
                    entity, values, storeTenant, entityKey, pkName, tenantHeader, operator));
        } else {
            saveAndMaybeStart(entity, values, storeTenant, entityKey, pkName, tenantHeader, operator);
        }
        return entityKey + ":" + Objects.toString(values.get(pkName), "");
    }

    private void saveAndMaybeStart(
            RenderedEntity entity,
            Map<String, Object> values,
            String storeTenant,
            String entityKey,
            String pkName,
            String tenantHeader,
            OperatorPrincipal operator) {
        if (entity.sharedRecordStorage()) {
            hybridEntities.save(entity, values, storeTenant);
        } else {
            tableEntities.save(entity, values, storeTenant);
        }
        maybeAutoStartRepairTicket(entity, values.get(pkName), tenantHeader, operator);
    }

    private void maybeAutoStartRepairTicket(
            RenderedEntity entity, Object recordIdRaw, String tenantHeader, OperatorPrincipal operator) {
        String entityKey = entity.entityKey();
        if (workflows == null || !isRepairTicket(entityKey)) {
            return;
        }
        if (operator == null) {
            throw new IllegalStateException(
                    "repair-ticket upsert requires an authenticated operator to auto-start workflow");
        }
        if (tenantHeader == null || tenantHeader.isBlank()) {
            throw new IllegalStateException(
                    "repair-ticket upsert requires X-Tenant-Id to auto-start workflow");
        }
        if (recordIdRaw == null || recordIdRaw.toString().isBlank()) {
            throw new IllegalStateException("repair-ticket upsert missing primary key for workflow start");
        }
        // WF-RT-4: pass declaration version from the same runtime entity used for upsert.
        // WF-RT-4：用本次 upsert 的生效实体版本写入工作流审计。
        String source = effective.resolutionSource(tenantHeader.trim(), DeclarationKind.ENTITY, entityKey);
        try {
            workflows.startIfAbsentAfterUpsert(
                    operator,
                    tenantHeader.trim(),
                    entityKey,
                    recordIdRaw.toString().trim(),
                    entity.version(),
                    source);
        } catch (RuntimeException ex) {
            // Fail closed: surface clearly (TX rolls back upsert when template present).
            // 失败关闭：明确抛出（有事务模板时回滚 upsert）。
            throw new IllegalStateException(
                    "repair-ticket upsert succeeded path but workflow start failed: " + ex.getMessage(), ex);
        }
    }

    private static boolean isRepairTicket(String entityKey) {
        return RepairTicketWorkflowDefinitions.REPAIR_TICKET.equals(entityKey);
    }
}
