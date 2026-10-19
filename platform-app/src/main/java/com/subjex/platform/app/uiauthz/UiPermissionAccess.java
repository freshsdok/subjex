package com.subjex.platform.app.uiauthz;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * UiPermissionAccess — 根据主体权限集合判定 Manifest 条目是否允许（ADR 0010 / PC-2）。
 * <p>
 * Login-only → true; non-empty {@code anyOf} → any match; else single {@code permission} membership.
 * UX only — backend APIs still enforce separately.
 * 登录即可 → true；anyOf 非空 → 任一命中；否则看单一 permission。仅 UX；API 仍各自强制。
 */
public final class UiPermissionAccess {

    private UiPermissionAccess() {}

    /**
     * Whether the principal may treat this UI entry as unlocked — 主体是否可将该 UI 条目视为解锁。
     */
    public static boolean allowed(UiPermissionEntry entry, Collection<String> permissionNames) {
        Objects.requireNonNull(entry, "entry");
        Set<String> held = permissionNames == null || permissionNames.isEmpty()
                ? Set.of()
                : Set.copyOf(permissionNames);
        if (entry.loginOnly()) {
            return true;
        }
        if (!entry.anyOf().isEmpty()) {
            for (String name : entry.anyOf()) {
                if (held.contains(name)) {
                    return true;
                }
            }
            return false;
        }
        return entry.permission() != null && held.contains(entry.permission());
    }
}
