package com.subjex.platform.app.storage;

import java.time.Instant;

/**
 * ObjectGrant — explicit in-tenant grant to a subject or an organization (ADR 0015).
 * 租户内显式授权，对象为新主体或组织。
 *
 * @param expiresAt null means no expiry — null 表示不过期
 */
public record ObjectGrant(
        String grantId,
        String tenantId,
        String objectKey,
        ObjectGrantKind granteeKind,
        String granteeId,
        Instant expiresAt,
        Instant createdAt,
        String createdBySubjectId) {}
