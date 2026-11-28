package com.subjex.platform.app.content;

import com.subjex.platform.contract.content.ContentPageDocument;
import com.subjex.platform.contract.content.ContentPageNotFoundException;
import com.subjex.platform.contract.content.ContentPageStatus;
import com.subjex.platform.contract.content.ContentPageSummary;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.storage.StoredObject;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.util.List;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;

/**
 * PublicContentPageService — unauthenticated read of published pages in the current tenant.
 * 无需登录即可阅读当前租户已发布的内容页。草稿不对外可见。
 */
public final class PublicContentPageService {

    private final JdbcContentPagePort pages;
    private final ObjectStorage storage;

    public PublicContentPageService(JdbcContentPagePort pages, ObjectStorage storage) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public List<ContentPageSummary> listPublished(String tenantId, Integer limit) {
        String tid = requirePublicTenant(tenantId);
        return pages.list(tid, ContentPageStatus.PUBLISHED, ContentPageRules.clampLimit(limit));
    }

    public ContentPageDocument getPublished(String tenantId, String slug) {
        String tid = requirePublicTenant(tenantId);
        return pages.getPublishedBySlug(tid, slug)
                .orElseThrow(() -> new ContentPageNotFoundException("published content page not found"));
    }

    /**
     * Stream an image object only when a published page in this tenant cites it.
     * 仅当本租户已发布页引用该对象时才读取图片字节。
     */
    public StoredObject publishedImage(String tenantId, String slug, String objectId) {
        String tid = requirePublicTenant(tenantId);
        JdbcContentPagePort.ObjectRef ref = pages.findPublishedImage(tid, slug, objectId)
                .orElseThrow(() -> new ContentPageNotFoundException("published content image not found"));
        return storage
                .find(tid, ref.objectKey())
                .orElseThrow(() -> new ContentPageNotFoundException("published content image not found"));
    }

    private static String requirePublicTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String tid = tenantId.trim();
        if (JdbcContentPagePort.RESERVED_TENANT.equals(tid)) {
            throw new AccessDeniedException("reserved-tenant");
        }
        return tid;
    }
}
