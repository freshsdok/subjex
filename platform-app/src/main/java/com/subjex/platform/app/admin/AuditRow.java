package com.subjex.platform.app.admin;

import java.time.Instant;

/**
 * AuditRow — 可读的审计行：何时、谁（登录名与身份）、做了什么、对什么、结果如何。
 * <p>
 * {@code actorLogin} is joined from the identity's account and is empty when that identity is not an operator login.
 * Optional declaration linkage mirrors {@code audit_entry} columns added in V19 (RT-6).
 * {@code actorLogin} 由身份对应的账号连接得到；该身份不是操作员登录时为空。
 * 可选声明关联对齐 V19 列（RT-6）。
 */
public record AuditRow(
        String auditEntryId,
        Instant occurredAt,
        String tenantId,
        String actorIdentityId,
        String actorLogin,
        String actionName,
        String actionTarget,
        String outcome,
        String entityKey,
        Integer declarationVersion,
        String resolutionSource) {}
