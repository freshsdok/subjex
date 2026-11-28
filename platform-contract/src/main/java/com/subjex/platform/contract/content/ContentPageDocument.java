package com.subjex.platform.contract.content;

import java.time.Instant;
import java.util.List;

/**
 * A tenant content page and its blocks in sort order.
 * 一份租户内容页及其按顺序排列的内容块。
 */
public record ContentPageDocument(
        String pageId,
        String tenantId,
        String slug,
        String title,
        ContentPageStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        List<ContentBlock> blocks) {

    public ContentPageDocument {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
