package com.subjex.platform.app.declaration;

import java.time.Instant;

/**
 * DeclarationPromote — 一次声明晋升审计行（内部 git commit，非 GitHub）。
 * <p>
 * One row per (tenant, kind, key, revision). Local commit only.
 * 每个 (租户, 种类, 键, 修订) 一行。仅本地提交。
 */
public record DeclarationPromote(
        String tenantId,
        DeclarationKind kind,
        String declarationKey,
        int revision,
        String gitCommitSha,
        Instant promotedAt,
        String promotedBySubjectId) {}
