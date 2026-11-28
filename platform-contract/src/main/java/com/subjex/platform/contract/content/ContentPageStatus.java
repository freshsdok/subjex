package com.subjex.platform.contract.content;

/**
 * ContentPageStatus — drafts are operator-only; only published pages are publicly readable.
 * 草稿仅操作员可见；只有已发布的页面可供公开阅读。
 */
public enum ContentPageStatus {
    DRAFT,
    PUBLISHED
}
