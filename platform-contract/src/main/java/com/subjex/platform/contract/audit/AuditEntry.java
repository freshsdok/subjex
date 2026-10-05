package com.subjex.platform.contract.audit;

import java.time.Instant;

/**
 * AuditEntry — 审计条目：一次可追溯动作的事实。
 * <p>
 * {@code actorIdentityId} names the Identity that acted, not a secret and not a login password.
 * {@code actionTarget} names what the action touched, for example a config key or a service name; it may be empty.
 * {@code actorIdentityId} 指执行动作的身份，不是密钥，也不是登录口令。
 * {@code actionTarget} 指这次动作碰到的对象，例如一个配置键或一个服务名，可以为空。
 */
public record AuditEntry(
        String auditEntryId,
        String tenantId,
        String actorIdentityId,
        String actionName,
        String actionTarget,
        AuditOutcome outcome,
        Instant occurredAt) {

    /**
     * An entry without a target — 没有动作对象的条目。
     */
    public AuditEntry(
            String auditEntryId,
            String tenantId,
            String actorIdentityId,
            String actionName,
            AuditOutcome outcome,
            Instant occurredAt) {
        this(auditEntryId, tenantId, actorIdentityId, actionName, null, outcome, occurredAt);
    }
}
