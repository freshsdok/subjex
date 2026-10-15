package com.subjex.platform.app.identity;

import com.subjex.platform.app.security.OperatorPermission;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ScopePermissionBridge — ID-2 minimal scopes↔permissions: OAuth scopes that match
 * {@link OperatorPermission} names become Spring authorities.
 * 最小桥接：与具名权限同名的 scope 成为授权。
 */
public final class ScopePermissionBridge {

    private static final Set<String> KNOWN = Arrays.stream(OperatorPermission.values())
            .map(OperatorPermission::permissionName)
            .collect(Collectors.toUnmodifiableSet());

    private ScopePermissionBridge() {}

    /** Filter space-separated scopes to known permission names — 把空格分隔 scope 滤成已知权限名。 */
    public static Set<String> toPermissions(String scopesRaw) {
        if (scopesRaw == null || scopesRaw.isBlank()) {
            return Set.of();
        }
        return toPermissions(Arrays.stream(scopesRaw.trim().split("\\s+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
    }

    public static Set<String> toPermissions(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : scopes) {
            if (s != null && KNOWN.contains(s)) {
                out.add(s);
            }
        }
        return Set.copyOf(out);
    }
}
