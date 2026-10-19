package com.subjex.platform.app.uiauthz;

import java.util.List;
import java.util.Objects;

/**
 * UiPermissionEntry — 一条 UI 绑定：菜单 / 页面 / 按钮 → 具名权限（ADR 0010）。
 * <p>
 * {@code permission} and/or {@code anyOf}; both absent = login-only (menu/page).
 * Wave 1 has no OrgScope fields. {@code anyOf} non-empty wins for gate evaluation later.
 * 仅 permission / anyOf；皆无则登录即可；第一波无 OrgScope；anyOf 非空时作门禁。
 */
public record UiPermissionEntry(
        String id,
        UiPermissionEntryKind kind,
        String href,
        String route,
        String permission,
        List<String> anyOf) {

    public UiPermissionEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        id = id.trim();
        if (id.isEmpty()) {
            throw new UiPermissionRejected("id must be non-blank");
        }
        href = blankToNull(href);
        route = blankToNull(route);
        permission = blankToNull(permission);
        anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
    }

    /** Login-only when no permission and no anyOf — 无 permission/anyOf 时仅需登录。 */
    public boolean loginOnly() {
        return permission == null && anyOf.isEmpty();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
