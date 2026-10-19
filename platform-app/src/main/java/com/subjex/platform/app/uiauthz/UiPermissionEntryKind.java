package com.subjex.platform.app.uiauthz;

/**
 * UiPermissionEntryKind — Manifest 条目种类：菜单 / 页面 / 按钮（ADR 0010）。
 * <p>
 * Wire values match YAML {@code kind}: {@code menu}, {@code page}, {@code button}.
 * 落库/YAML 值与 {@code kind} 一致。
 */
public enum UiPermissionEntryKind {

    MENU("menu"),
    PAGE("page"),
    BUTTON("button");

    private final String wireName;

    UiPermissionEntryKind(String wireName) {
        this.wireName = wireName;
    }

    /** YAML / wire name — YAML / 线名。 */
    public String wireName() {
        return wireName;
    }

    /** Parse wire name; blank or unknown → reject — 解析；空或未知拒绝。 */
    public static UiPermissionEntryKind fromWire(String wireName) {
        if (wireName == null || wireName.isBlank()) {
            throw new UiPermissionRejected("kind is required");
        }
        String trimmed = wireName.trim();
        for (UiPermissionEntryKind kind : values()) {
            if (kind.wireName.equals(trimmed)) {
                return kind;
            }
        }
        throw new UiPermissionRejected("unknown kind: " + trimmed);
    }
}
