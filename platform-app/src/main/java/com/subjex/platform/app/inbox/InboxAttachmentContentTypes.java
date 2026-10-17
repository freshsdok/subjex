package com.subjex.platform.app.inbox;

import java.util.Locale;
import java.util.Set;

/**
 * InboxAttachmentContentTypes — ADR 0009 allowlist for inbox attachments.
 * 站内信附件 MIME 白名单（ADR 0009）。
 */
public final class InboxAttachmentContentTypes {

    private static final Set<String> ALLOWED = Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/webp",
            "text/plain",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private InboxAttachmentContentTypes() {}

    /** Normalize and validate; throws if not allowed — 规范化并校验；不在白名单则抛错. */
    public static String requireAllowed(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType required");
        }
        String type = contentType.trim();
        int semi = type.indexOf(';');
        if (semi >= 0) {
            type = type.substring(0, semi).trim();
        }
        type = type.toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(type)) {
            throw new IllegalArgumentException("contentType not allowed for inbox attachment: " + type);
        }
        return type;
    }

    public static boolean isAllowed(String contentType) {
        try {
            requireAllowed(contentType);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
