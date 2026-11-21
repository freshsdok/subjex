package com.subjex.platform.app.inbox;

import com.subjex.platform.app.storage.ObjectContentTypes;
import java.util.Set;

/**
 * InboxAttachmentContentTypes — ADR 0009 allowlist; delegates to the shared {@link ObjectContentTypes}.
 * 站内信附件 MIME 白名单；委托给授权对象轨共用名单，避免第二份互相冲突的列表。
 */
public final class InboxAttachmentContentTypes {

    private InboxAttachmentContentTypes() {}

    /** Normalize and validate; throws if not allowed — 规范化并校验；不在白名单则抛错. */
    public static String requireAllowed(String contentType) {
        return ObjectContentTypes.requireAllowed(contentType);
    }

    public static boolean isAllowed(String contentType) {
        return ObjectContentTypes.isAllowed(contentType);
    }

    /** Shared allowlist — 共用白名单。 */
    public static Set<String> allowed() {
        return ObjectContentTypes.allowed();
    }
}
