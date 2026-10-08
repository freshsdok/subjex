package com.subjex.platform.app.declaration;

import java.time.Instant;

/**
 * DeclarationMigration — 一条声明 schema 迁移队列行（审阅后可执行）。
 * <p>
 * One queued job per id. {@code declarationRevision} mirrors {@code declaration_promote.revision}.
 * Apply is MQ-2 ({@link DeclarationMigrationApplyService}); entity promote binds to APPLIED.
 * 每个 id 一条队列任务。{@code declarationRevision} 对齐晋升修订号。执行见 MQ-2；实体晋升绑定 APPLIED。
 */
public record DeclarationMigration(
        String migrationId,
        String tenantId,
        DeclarationKind kind,
        String declarationKey,
        int declarationRevision,
        String sqlText,
        String status,
        Instant createdAt,
        String createdBySubjectId,
        Instant updatedAt,
        Instant appliedAt,
        String errorMessage) {}
