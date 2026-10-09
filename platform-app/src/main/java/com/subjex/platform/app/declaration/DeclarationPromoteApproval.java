package com.subjex.platform.app.declaration;

import java.time.Instant;

/** DeclarationPromoteApproval — 一条晋升双人确认请求。 */
public record DeclarationPromoteApproval(
        String approvalId,
        String tenantId,
        DeclarationKind kind,
        String declarationKey,
        int revision,
        String requestedBySubjectId,
        String approvalState,
        Instant createdAt,
        Instant consumedAt,
        String consumedBySubjectId) {}
