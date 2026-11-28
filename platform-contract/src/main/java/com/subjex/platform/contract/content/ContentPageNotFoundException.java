package com.subjex.platform.contract.content;

/**
 * ContentPageNotFoundException — The content page does not exist in this tenant. / 该内容页在本租户不存在。
 */
public final class ContentPageNotFoundException extends RuntimeException {

    public static final String REASON = "content-page-not-found";

    public ContentPageNotFoundException(String message) {
        super(message);
    }
}
