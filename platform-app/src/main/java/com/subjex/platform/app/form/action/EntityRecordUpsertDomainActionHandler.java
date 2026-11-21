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
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.workflow.WorkflowCatalog;
import com.subjex.platform.app.workflow.WorkflowService;
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
 * WP4-2: after a successful save, auto-start a workflow instance when the entity is bound to a
 * machine with {@code autoStartOnUpsert} (idempotent). Upsert + start run in one
 * {@link TransactionTemplate} when provided so a start failure rolls back the row (fail-closed).
 * Without a TX template (unit tests), start errors still propagate after save.
 * WP4-2：保存成功后，若该实体绑定的机器声明了自动启动，则幂等起实例；有事务模板则同事务失败关闭。
 * AuthZ-1g: entity tenant/permission gate via {@link DeclarationAccess#requireVia} / {@link PolicyEngine}.
 * AuthZ-1g：实体租户/权限门禁经 PolicyEngine。
 */
@Component
public final class EntityRecordUpsertDomainActionHandler implements DomainActionHandler {

    private final EffectiveDeclarationService effective;
    private final GenericEntityStore tableEntities;
    private final HybridEntityStore hybridEntities;
    private final PolicyEngine policyEngine;
    private final WorkflowService workflows;
    private final WorkflowCatalog workflowCatalog;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public EntityRecordUpsertDomainActionHandler(
            EffectiveDeclarationService effective,
            GenericEntityStore tableEntities,
            HybridEntityStore hybridEntities,
            PolicyEngine policyEngine,
            WorkflowService workflows,
            WorkflowCatalog workflowCatalog,
            TransactionTemplate transactionTemplate) {
        this.effective = Objects.requireNonNull(effective, "effective");
        this.tableEntities = Objects.requireNonNull(tableEntities, "tableEntities");
        this.hybridEntities = Objects.requireNonNull(hybridEntities, "hybridEntities");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.workflowCatalog = Objects.requireNonNull(workflowCatalog, "workflowCatalog");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    }

    /**
     * Test helper without workflow auto-start — 无自动起实例的测试构造。
     */
    public EntityRecordUpsertDomainActionHandler(
            EffectiveDeclarationService effective,
            GenericEntityStore tableEntities,
            HybridEntityStore hybridEntities,
            PolicyEngine policyEngine) {
        this.effective = Objects.requireNonNull(effective, "effective");
        this.tableEntities = Objects.requireNonNull(tableEntities, "tableEntities");
        this.hybridEntities = Objects.requireNonNull(hybridEntities, "hybridEntities");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
        this.workflows = null;
        this.workflowCatalog = null;
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
        // AuthZ-1g: full entity gate via PolicyEngine (permission + tenantScoped) — 经 PolicyEngine 做权限+租户。
        // Prefer entity.permission over form.permission so entity declaration is SoT for this write.
        // 以实体声明 permission 为准（表单侧 FormSubmission 已先检 form.permission）。
        DeclarationAccess.requireVia(
                policyEngine,
                operator,
                entity.permission(),
                entity.tenantScoped(),
                tenantHeader,
                AccessResource.of("entity", entityKey),
                AccessAction.of("upsert"));
        Map<String, Object> values = new LinkedHashMap<>();
        for (EntityField field : entity.fields()) {
            if (accepted.containsKey(field.name())) {
                values.put(field.name(), accepted.get(field.name()));
            }
        }
        String pkName = entity.primaryKey().name();
        String storeTenant = entity.tenantScoped() ? tenantHeader : null;
        if (transactionTemplate != null && autoStarts(entityKey)) {
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
        maybeAutoStartWorkflow(entity, values.get(pkName), tenantHeader, operator);
    }

    private void maybeAutoStartWorkflow(
            RenderedEntity entity, Object recordIdRaw, String tenantHeader, OperatorPrincipal operator) {
        String entityKey = entity.entityKey();
        if (workflows == null || !autoStarts(entityKey)) {
            return;
        }
        if (operator == null) {
            throw new IllegalStateException(
                    "workflow auto-start requires an authenticated operator");
        }
        if (tenantHeader == null || tenantHeader.isBlank()) {
            throw new IllegalStateException(
                    "workflow auto-start requires X-Tenant-Id");
        }
        if (recordIdRaw == null || recordIdRaw.toString().isBlank()) {
            throw new IllegalStateException("workflow auto-start missing primary key");
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
                    "entity upsert succeeded path but workflow start failed: " + ex.getMessage(), ex);
        }
    }

    private boolean autoStarts(String entityKey) {
        return workflowCatalog != null && workflowCatalog.findAutoStart(entityKey).isPresent();
    }
}
