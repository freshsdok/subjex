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
 * The tenant is {@code platform}, the actor is the operator's identity, and the target is the key or service name.
 * It writes through the single {@link AuditPort}; there is no second audit channel.
 * 租户是 {@code platform}，操作者是操作员的身份，对象是配置键或服务名。
 * 它经唯一的 {@link AuditPort} 写入，没有第二条审计通道。
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
}
