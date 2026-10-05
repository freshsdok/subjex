package com.subjex.platform.app.security;

import org.springframework.security.access.AccessDeniedException;

/**
 * DeclarationPermissionDeniedException — 声明权限拒绝：带上 YAML 里的权限名，便于控制台展示缺哪一项。
 */
public final class DeclarationPermissionDeniedException extends AccessDeniedException {

    private final String permission;

    public DeclarationPermissionDeniedException(String permission) {
        super((permission == null || permission.isBlank() ? "permission" : permission) + " required");
        this.permission = permission == null || permission.isBlank() ? "permission" : permission;
    }

    public String permission() {
        return permission;
    }
}
