package com.subjex.platform.app.uiauthz;

import java.util.List;
import java.util.Objects;

/**
 * UiPermissionManifest — 一份客户端 UI 权限 Manifest（ADR 0010）。
 * <p>
 * {@code client} e.g. {@code console}; {@code entries} ordered as authored (nav tour order).
 * 客户端如 console；entries 保持作者顺序（侧栏巡览序）。
 */
public record UiPermissionManifest(
        int version, String client, UiPermissionManifestDefaults defaults, List<UiPermissionEntry> entries) {

    public static final String CLIENT_CONSOLE = "console";

    public UiPermissionManifest {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(defaults, "defaults");
        Objects.requireNonNull(entries, "entries");
        client = client.trim();
        if (client.isEmpty()) {
            throw new UiPermissionRejected("client must be non-blank");
        }
        if (version < 1) {
            throw new UiPermissionRejected("version must be >= 1");
        }
        entries = List.copyOf(entries);
    }

    /** Menu entries in manifest order — 按 Manifest 顺序的菜单项。 */
    public List<UiPermissionEntry> menus() {
        return entries.stream().filter(e -> e.kind() == UiPermissionEntryKind.MENU).toList();
    }
}
