package com.subjex.platform.contract.audit;

import java.time.Instant;

/**
 * AuditEntry — 审计条目：一次可追溯动作的事实。
 * <p>
 * {@code actorIdentityId} names the Identity that acted, not a secret and not a login password.
 * {@code actorIdentityId} 指执行动作的身份，不是密钥，也不是登录口令。
 */
public record AuditEntry(
        String auditEntryId,
        String tenantId,
        String actorIdentityId,
        String actionName,
        AuditOutcome outcome,
        Instant occurredAt) {
}
