package com.subjex.platform.app.org.legacy;

import com.subjex.platform.app.security.OrganizationScope;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * OrgScope — <strong>deprecated</strong> legacy mirror of {@link OrganizationScope}.
 * Prefer {@link OrganizationScope}. O8-3 already isolated compatibility under
 * {@code org.legacy}; this type remains only for {@code JdbcOrgDirectory} /
 * {@code /api/v1/org/**} adapters (not for new code).
 * Field names match OrganizationScope (O8-2): {@code rootOrganizationIds} / {@code organizationIds}.
 * <p>
 * 已弃用；正式用 {@link OrganizationScope}。O8-3 已把兼容层收到 {@code org.legacy}；
 * 本类型仅供旧目录/旧 {@code /api/v1/org/**} 适配，新代码不要依赖。
 */
@Deprecated(since = "O8-1", forRemoval = false)
public record OrgScope(String mode, List<String> rootOrganizationIds, List<String> organizationIds) {

    public static final String MODE_NONE = OrganizationScope.MODE_NONE;
    public static final String MODE_UNRESTRICTED = OrganizationScope.MODE_UNRESTRICTED;
    public static final String MODE_SELF = OrganizationScope.MODE_SELF;
    public static final String MODE_SELF_AND_DESCENDANTS = OrganizationScope.MODE_SELF_AND_DESCENDANTS;
    public static final String MODE_EXPLICIT = OrganizationScope.MODE_EXPLICIT;

    private static final Set<String> KNOWN_MODES = Set.of(
            MODE_NONE, MODE_UNRESTRICTED, MODE_SELF, MODE_SELF_AND_DESCENDANTS, MODE_EXPLICIT);

    public OrgScope {
        mode = normalizeMode(mode);
        rootOrganizationIds = List.copyOf(normalizeIds(rootOrganizationIds));
        organizationIds = List.copyOf(normalizeIds(organizationIds));
        if (MODE_NONE.equals(mode) || MODE_UNRESTRICTED.equals(mode)) {
            rootOrganizationIds = List.of();
            organizationIds = List.of();
        }
    }

    /** Convert to canonical {@link OrganizationScope} (Legacy → New). */
    public OrganizationScope toOrganizationScope() {
        return new OrganizationScope(mode, rootOrganizationIds, organizationIds);
    }

    /** Fail-closed empty scope — 失败关闭空范围。 */
    public static OrgScope none() {
        return new OrgScope(MODE_NONE, List.of(), List.of());
    }

    /** Unrestricted — 无限制。 */
    public static OrgScope unrestricted() {
        return new OrgScope(MODE_UNRESTRICTED, List.of(), List.of());
    }

    /** SELF roots only — 仅 SELF 根。 */
    public static OrgScope self(Collection<String> membershipOrgIds) {
        List<String> ids = List.copyOf(normalizeIds(membershipOrgIds));
        return new OrgScope(MODE_SELF, ids, ids);
    }

    /** SELF + descendants — SELF 及下级。 */
    public static OrgScope selfAndDescendants(Collection<String> roots, Collection<String> expanded) {
        return new OrgScope(
                MODE_SELF_AND_DESCENDANTS,
                List.copyOf(normalizeIds(roots)),
                List.copyOf(normalizeIds(expanded)));
    }

    /** Explicit allow-list — 显式白名单。 */
    public static OrgScope explicit(Collection<String> organizationIds) {
        List<String> ids = List.copyOf(normalizeIds(organizationIds));
        return new OrgScope(MODE_EXPLICIT, ids, ids);
    }

    /** Bridge from canonical scope (legacy adapters). */
    public static OrgScope fromOrganizationScope(OrganizationScope scope) {
        if (scope == null) {
            return null;
        }
        return new OrgScope(scope.mode(), scope.rootOrganizationIds(), scope.organizationIds());
    }

    /** MODE_NONE — 是否 NONE。 */
    public boolean isNone() {
        return MODE_NONE.equals(mode);
    }

    /** MODE_UNRESTRICTED — 是否无限制。 */
    public boolean isUnrestricted() {
        return MODE_UNRESTRICTED.equals(mode);
    }

    public boolean contains(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return false;
        }
        if (isUnrestricted()) {
            return true;
        }
        if (isNone()) {
            return false;
        }
        return organizationIds.contains(organizationId.trim());
    }

    public String summary() {
        if (isNone() || isUnrestricted()) {
            return mode;
        }
        return mode + " roots=" + rootOrganizationIds + " orgs=" + organizationIds;
    }

    private static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return MODE_SELF_AND_DESCENDANTS;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_MODES.contains(trimmed)) {
            throw new IllegalArgumentException("unknown OrgScope mode: " + raw);
        }
        return trimmed;
    }

    private static List<String> normalizeIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<String> ordered = new TreeSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                ordered.add(id.trim());
            }
        }
        return List.copyOf(ordered);
    }
}
