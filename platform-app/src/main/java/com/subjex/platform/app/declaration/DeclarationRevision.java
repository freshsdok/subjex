package com.subjex.platform.app.declaration;

import java.time.Instant;

/**
 * DeclarationRevision — 租户声明草稿的一次修订行。
 * <p>
 * Tenant-scoped only (no platform-global drafts). {@code draftState} is {@code DRAFT} or {@code PROMOTED} (Z5-1).
 * YAML text only — no auto-DDL from this row.
 * 仅租户隔离（无平台全局草稿）。{@code draftState} 为 {@code DRAFT} 或 {@code PROMOTED}（Z5-1）。
 * 只存 YAML 文本——不据此自动改表。
 */
public record DeclarationRevision(
        String tenantId,
        DeclarationKind kind,
        String declarationKey,
        int revision,
        String yamlBody,
        String draftState,
        Instant updatedAt,
        String updatedBySubjectId) {}
