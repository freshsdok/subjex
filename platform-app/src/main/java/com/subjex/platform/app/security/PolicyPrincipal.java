package com.subjex.platform.app.security;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * PolicyPrincipal — 策略引擎主体（Cedar principal / Casbin sub 子集）。
 * <p>
 * Holds {@code subjectId} and granted permission names only. Not a role name bag for scope.
 * Full Cedar/Casbin engines may wrap richer attributes later; this subset matches SQL RBAC today.
 * 仅主体标识与已授权限名。不用角色名表达范围。完整引擎可后加属性；本子集对齐当前 SQL RBAC。
 */
public record PolicyPrincipal(String subjectId, Set<String> permissionNames) {

    public PolicyPrincipal {
        subjectId = subjectId == null || subjectId.isBlank() ? null : subjectId.trim();
        permissionNames = permissionNames == null ? Set.of() : Set.copyOf(permissionNames);
    }

    public static PolicyPrincipal of(String subjectId, Collection<String> permissionNames) {
        return new PolicyPrincipal(subjectId, permissionNames == null ? Set.of() : Set.copyOf(permissionNames));
    }

    /** Bridge from signed-in operator — 从已登录操作员构造。 */
    public static PolicyPrincipal from(OperatorPrincipal operator) {
        if (operator == null) {
            return new PolicyPrincipal(null, Set.of());
        }
        return new PolicyPrincipal(operator.subjectId(), operator.permissionNames());
    }
}
