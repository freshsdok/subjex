package com.subjex.platform.app.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * OperatorPrincipal — 已登录的操作员或服务账号：账号/客户端、主体、身份、权限；服务账号可带绑定租户。
 * <p>
 * {@code identityId} is the Identity written into audit entries. The password hash never leaves the sign-in check.
 * Service accounts (ID-2) use login {@code sa:<clientId>}, empty password hash, and optional {@link #boundTenantId()}.
 * {@code identityId} 是写进审计条目的身份。口令摘要不离开登录核对。
 * 服务账号登录名为 {@code sa:<clientId>}，可带绑定租户。
 */
public final class OperatorPrincipal implements UserDetails {

    public static final String SERVICE_ACCOUNT_PREFIX = "sa:";

    private final String loginName;
    private final String passwordHash;
    private final String identityId;
    private final String subjectId;
    private final Set<String> permissionNames;
    private final boolean active;
    /** Tenant bound for service-account tokens; null for human operators — 服务账号绑定租户；人工操作员为空。 */
    private final String boundTenantId;

    public OperatorPrincipal(
            String loginName,
            String passwordHash,
            String identityId,
            String subjectId,
            Set<String> permissionNames,
            boolean active) {
        this(loginName, passwordHash, identityId, subjectId, permissionNames, active, null);
    }

    public OperatorPrincipal(
            String loginName,
            String passwordHash,
            String identityId,
            String subjectId,
            Set<String> permissionNames,
            boolean active,
            String boundTenantId) {
        this.loginName = loginName;
        this.passwordHash = passwordHash;
        this.identityId = identityId;
        this.subjectId = subjectId;
        this.permissionNames = Set.copyOf(permissionNames);
        this.active = active;
        this.boundTenantId =
                boundTenantId == null || boundTenantId.isBlank() ? null : boundTenantId.trim();
    }

    /**
     * Build principal from OP client_credentials JWT claims (ID-2).
     * 从 client_credentials JWT 声明构造主体。
     */
    public static OperatorPrincipal serviceAccount(String clientId, String tenantId, Set<String> permissions) {
        String id = requireClientId(clientId);
        return new OperatorPrincipal(
                SERVICE_ACCOUNT_PREFIX + id,
                "",
                SERVICE_ACCOUNT_PREFIX + id,
                id,
                permissions == null ? Set.of() : permissions,
                true,
                tenantId);
    }

    private static String requireClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId required");
        }
        return clientId.trim();
    }

    /** Identity that acts in the platform tenant — 在平台租户里行动的身份。 */
    public String identityId() {
        return identityId;
    }

    /** Subject the operator is — 操作员本人这个主体（服务账号为 client_id）。 */
    public String subjectId() {
        return subjectId;
    }

    /** Permission names this operator holds — 该操作员持有的权限名。 */
    public Set<String> permissionNames() {
        return permissionNames;
    }

    /** Bound tenant for SA tokens; null for humans — 服务账号绑定租户。 */
    public String boundTenantId() {
        return boundTenantId;
    }

    public boolean serviceAccount() {
        return boundTenantId != null || (loginName != null && loginName.startsWith(SERVICE_ACCOUNT_PREFIX));
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
