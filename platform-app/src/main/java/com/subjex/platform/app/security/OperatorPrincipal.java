package com.subjex.platform.app.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * OperatorPrincipal — 已登录的操作员：哪个账号登录、代表哪个主体、以哪个身份行动、手里有哪些权限。
 * <p>
 * {@code identityId} is the Identity written into audit entries. The password hash never leaves the sign-in check.
 * {@code identityId} 是写进审计条目的身份。口令摘要不离开登录核对。
 */
public final class OperatorPrincipal implements UserDetails {

    private final String loginName;
    private final String passwordHash;
    private final String identityId;
    private final String subjectId;
    private final Set<String> permissionNames;
    private final boolean active;

    public OperatorPrincipal(
            String loginName,
            String passwordHash,
            String identityId,
            String subjectId,
            Set<String> permissionNames,
            boolean active) {
        this.loginName = loginName;
        this.passwordHash = passwordHash;
        this.identityId = identityId;
        this.subjectId = subjectId;
        this.permissionNames = Set.copyOf(permissionNames);
        this.active = active;
    }

    /** Identity that acts in the platform tenant — 在平台租户里行动的身份。 */
    public String identityId() {
        return identityId;
    }

    /** Subject the operator is — 操作员本人这个主体。 */
    public String subjectId() {
        return subjectId;
    }

    /** Permission names this operator holds — 该操作员持有的权限名。 */
    public Set<String> permissionNames() {
        return permissionNames;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<SimpleGrantedAuthority> granted = permissionNames.stream()
                .sorted()
                .map(SimpleGrantedAuthority::new)
                .toList();
        return granted;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return loginName;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
