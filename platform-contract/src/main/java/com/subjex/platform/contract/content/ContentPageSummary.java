package com.subjex.platform.contract.content;

import java.time.Instant;

/**
 * List row for a content page (no blocks).
 * 内容页列表行（不含内容块）。
 */
public record ContentPageSummary(
        String pageId,
        String tenantId,
        String slug,
        String title,
        ContentPageStatus status,
        Instant updatedAt,
        Instant publishedAt) {}
