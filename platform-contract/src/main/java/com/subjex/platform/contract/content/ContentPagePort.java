package com.subjex.platform.contract.content;

import java.util.List;
import java.util.Optional;

/**
 * ContentPagePort — tenant-scoped content pages. Public reads use {@link #getPublishedBySlug}.
 * 按租户的内容页。公开阅读走 {@link #getPublishedBySlug}。
 */
public interface ContentPagePort {

    List<ContentPageSummary> list(String tenantId, ContentPageStatus status, int limit);

    ContentPageDocument get(String tenantId, String pageId);

    /**
     * Published page by slug, or empty when missing or still a draft.
     * 按别名取已发布页；不存在或仍为草稿时返回空。
     */
    Optional<ContentPageDocument> getPublishedBySlug(String tenantId, String slug);

    ContentPageDocument create(SaveContentPageCommand command);

    ContentPageDocument update(SaveContentPageCommand command);

    ContentPageDocument publish(String tenantId, String pageId, String actorSubjectId);

    ContentPageDocument unpublish(String tenantId, String pageId, String actorSubjectId);
}
