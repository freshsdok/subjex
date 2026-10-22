package com.subjex.platform.app.security;

import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * OperatorActionAudit — 操作员动作审计：把一次配置覆盖或服务登记记成一条审计条目。
 * <p>
 * Default {@link #record} writes tenant {@code platform}. Form submit {@code audit.write} uses
 * {@link #recordFormEffect}; workflow uses {@link #recordWorkflow} (WF-RT-4) with request tenant
 * plus entity / declaration linkage. One {@link AuditPort} only.
 * 默认 {@link #record} 写租户 {@code platform}。表单走 {@link #recordFormEffect}；工作流走
 * {@link #recordWorkflow}（WF-RT-4）带请求租户与实体/声明关联。仍唯一 {@link AuditPort}。
 */
public final class OperatorActionAudit {

    /** Config key overridden — 覆盖了一个配置键。 */
    public static final String CONFIG_OVERRIDE = "config.override";
    /** Config restore from history (Config-7a explicit put) — 从历史显式恢复写入。 */
    public static final String CONFIG_RESTORE = "config.restore";
    /** Service endpoint registered — 登记了一个服务端点。 */
    public static final String REGISTRY_REGISTER = "registry.register";

    private final AuditPort auditPort;
    private final Clock clock;

    public OperatorActionAudit(AuditPort auditPort, Clock clock) {
        this.auditPort = Objects.requireNonNull(auditPort, "auditPort");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void record(OperatorPrincipal operator, String actionName, String actionTarget, AuditOutcome outcome) {
        Objects.requireNonNull(operator, "operator");
        auditPort.record(new AuditEntry(
                UUID.randomUUID().toString(),
                JdbcOperatorDirectory.OPERATOR_TENANT,
                operator.identityId(),
                actionName,
                actionTarget,
                outcome,
                clock.instant()));
    }

    /**
     * Form {@code audit.write}: tenant + actor + entity + declaration version / source when known —
     * 表单审计：租户、操作者、实体键、声明版本与解析来源（可知时）。
     */
    public void recordFormEffect(
            OperatorPrincipal operator,
            String tenantId,
            String actionName,
            String actionTarget,
            String entityKey,
            int declarationVersion,
            String resolutionSource,
            AuditOutcome outcome) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(outcome, "outcome");
        String tid = requireText(tenantId, "tenantId");
        String action = requireText(actionName, "actionName");
        auditPort.record(new AuditEntry(
                UUID.randomUUID().toString(),
                tid,
                operator.identityId(),
                action,
                actionTarget == null ? "" : actionTarget,
                outcome,
                clock.instant(),
                blankToNull(entityKey),
                declarationVersion,
                blankToNull(resolutionSource)));
    }

    /**
     * WF-RT-4 workflow audit: tenant + instance target + entity + declaration version when known —
     * 工作流审计：租户、实例目标、实体键、可知时的声明版本。
     */
    public void recordWorkflow(
            OperatorPrincipal operator,
            String tenantId,
            String actionName,
            String actionTarget,
            String entityKey,
            Integer declarationVersion,
            String resolutionSource,
            AuditOutcome outcome) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(outcome, "outcome");
        String tid = requireText(tenantId, "tenantId");
        String action = requireText(actionName, "actionName");
        auditPort.record(new AuditEntry(
                UUID.randomUUID().toString(),
                tid,
                operator.identityId(),
                action,
                actionTarget == null ? "" : actionTarget,
                outcome,
                clock.instant(),
                blankToNull(entityKey),
                declarationVersion,
                blankToNull(resolutionSource)));
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
