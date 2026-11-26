package com.subjex.platform.app.storage;

import java.util.Locale;
import java.util.Set;

/**
 * ObjectContentTypes — shared MIME allowlist for the authorized-object track and inbox (ADR 0009 / 0015 D5).
 * 授权对象轨与站内信共用的 MIME 白名单。名单以 ADR 0009 为起点；医疗/影像类型在内核中无特殊地位。
 */
public final class ObjectContentTypes {

    private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/webp");

    private static final Set<String> ALLOWED = Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/webp",
            "text/plain",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private ObjectContentTypes() {}

    /** Allowed types (immutable) — 允许的类型（不可变）。 */
    public static Set<String> allowed() {
        return ALLOWED;
    }

    /**
     * Normalize and validate; throws if not on the shared list.
     * 规范化并校验；不在共用名单则抛错、失败关闭。
     */
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
            throw new IllegalArgumentException("contentType not allowed: " + type);
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

    /**
     * True when the type is an allowed still image suitable for an avatar.
     * 是否为允许用作头像的静态图片类型。
     */
    public static boolean isImage(String contentType) {
        try {
            return IMAGES.contains(requireAllowed(contentType));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
