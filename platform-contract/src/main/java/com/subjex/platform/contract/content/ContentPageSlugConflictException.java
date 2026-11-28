package com.subjex.platform.contract.content;

/**
 * ContentPageSlugConflictException — The slug is already used by another page in this tenant. / 该别名已被本租户的另一页面使用。
 */
public final class ContentPageSlugConflictException extends RuntimeException {

    public static final String REASON = "content-slug-conflict";

    public ContentPageSlugConflictException(String message) {
        super(message);
    }
}
