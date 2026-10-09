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
 * {@link #recordFormEffect} so the entry carries request tenant plus declaration linkage (RT-6).
 * It writes through the single {@link AuditPort}; there is no second audit channel.
 * 默认 {@link #record} 写租户 {@code platform}。表单提交审计走 {@link #recordFormEffect}，带请求租户与声明关联（RT-6）。
 * 经唯一的 {@link AuditPort} 写入，没有第二条审计通道。
 */
public final class OperatorActionAudit {

    /** Config key overridden — 覆盖了一个配置键。 */
    public static final String CONFIG_OVERRIDE = "config.override";
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
