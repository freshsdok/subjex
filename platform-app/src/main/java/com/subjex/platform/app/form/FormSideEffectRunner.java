package com.subjex.platform.app.form;

import com.subjex.form.render.DeclaredEffect;
import com.subjex.form.render.RenderedForm;
import com.subjex.form.render.SideEffectKey;
import com.subjex.platform.app.security.JdbcOperatorDirectory;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.extension.PlatformExtension;
import com.subjex.platform.contract.task.TaskCommand;
import com.subjex.platform.contract.task.TaskKind;
import com.subjex.platform.contract.task.TaskMessagePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * FormSideEffectRunner — 表单副作用执行器：提交校验与落库业务动作之后，按声明调用已有端口。
 * <p>
 * Allowed keys come from the checked-in catalog ({@code audit.write}, {@code task.enqueue},
 * {@code extension.invoke}). Unknown keys are refused (fail-closed). Effects run under the same
 * operator subject; tenant defaults to {@code platform} when the form is not tenant-scoped.
 * Each run returns a short outcome list for the submit response / console debug panel.
 * 允许的键来自检入目录。未知键拒绝（失败关闭）。副作用在同一操作员主体下执行；非租户隔离表单用 {@code platform}。
 * 每次执行返回简短结果列表，供提交响应与控制台调试面板。
 */
public final class FormSideEffectRunner {

    public static final String OUTCOME_OK = "ok";

    private final OperatorActionAudit audit;
    private final TaskMessagePort tasks;
    private final List<PlatformExtension> extensions;

    public FormSideEffectRunner(
            OperatorActionAudit audit, TaskMessagePort tasks, List<PlatformExtension> extensions) {
        this.audit = Objects.requireNonNull(audit, "audit");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.extensions = List.copyOf(Objects.requireNonNull(extensions, "extensions"));
    }

    /**
     * Run every declared effect for this form — 执行该表单声明的每一项副作用。
     *
     * @param tenantId request tenant header, may be null when the form is not tenant-scoped
     *                 请求租户头；非租户隔离表单可为 null
     * @return one outcome per declared effect, in declaration order / 按声明顺序每项一条结果
     */
    public List<EffectOutcomeDocument> run(
            RenderedForm form,
            Map<String, Object> accepted,
            OperatorPrincipal operator,
            String tenantId) {
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(operator, "operator");
        String effectTenant = resolveTenant(form, tenantId);
        List<EffectOutcomeDocument> outcomes = new ArrayList<>();
        for (DeclaredEffect effect : form.effects()) {
            invoke(effect, accepted, operator, effectTenant);
            outcomes.add(new EffectOutcomeDocument(effect.key().key(), OUTCOME_OK));
        }
        return List.copyOf(outcomes);
    }

    private void invoke(
            DeclaredEffect effect, Map<String, Object> accepted, OperatorPrincipal operator, String tenantId) {
        SideEffectKey key = effect.key();
        Map<String, String> params = effect.params();
        switch (key) {
            case AUDIT_WRITE -> writeAudit(params, accepted, operator);
            case TASK_ENQUEUE -> enqueueTask(params, operator, tenantId);
            case EXTENSION_INVOKE -> invokeExtension(params);
            default -> throw new IllegalArgumentException("unknown effect key " + key.key());
        }
    }

    private void writeAudit(Map<String, String> params, Map<String, Object> accepted, OperatorPrincipal operator) {
        String actionName = requiredParam(params, "actionName");
        String targetField = params.get("actionTargetField");
        String actionTarget = "";
        if (targetField != null) {
            Object raw = accepted.get(targetField);
            actionTarget = raw == null ? "" : Objects.toString(raw, "");
        }
        audit.record(operator, actionName, actionTarget, AuditOutcome.ALLOWED);
    }

    private void enqueueTask(Map<String, String> params, OperatorPrincipal operator, String tenantId) {
        String stepName = requiredParam(params, "stepName");
        TaskKind taskKind = TaskKind.valueOf(requiredParam(params, "taskKind"));
        if (taskKind != TaskKind.DETERMINISTIC) {
            throw new IllegalArgumentException("form effects only support DETERMINISTIC task.enqueue");
        }
        tasks.submit(new TaskCommand(
                tenantId,
                "form-effect-" + UUID.randomUUID(),
                operator.identityId(),
                taskKind,
                stepName,
                true,
                null,
                null,
                null));
    }

    private void invokeExtension(Map<String, String> params) {
        String extensionName = requiredParam(params, "extensionName");
        boolean found = extensions.stream().anyMatch(ext -> extensionName.equals(ext.extensionName()));
        if (!found) {
            throw new IllegalArgumentException("unknown extension " + extensionName);
        }
    }

    private static String resolveTenant(RenderedForm form, String tenantId) {
        if (form.tenantScoped()) {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenant is required for tenant-scoped form effects");
            }
            return tenantId;
        }
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId;
        }
        return JdbcOperatorDirectory.OPERATOR_TENANT;
    }

    private static String requiredParam(Map<String, String> params, String name) {
        String value = params.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("effect param " + name + " is missing");
        }
        return value;
    }
}
