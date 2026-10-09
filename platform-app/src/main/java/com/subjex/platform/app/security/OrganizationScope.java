package com.subjex.platform.app.security;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * OrganizationScope — organization scope for authorization (O8-1/O8-2).
 * <p>
 * Modes: {@link #MODE_NONE}, {@link #MODE_UNRESTRICTED}, {@link #MODE_SELF},
 * {@link #MODE_SELF_AND_DESCENDANTS}, {@link #MODE_EXPLICIT}. Derived at Context time from
 * Membership + OrganizationRelation(CONTAINS) — never stored on Membership; never from the
 * dropped {@code org_unit_organization_map} (O8). Null on {@link AccessDecision} / {@link PolicyContext}
 * means unspecified; {@link AccessChecker} treats unspecified + resource organization id as
 * fail-closed (not UNRESTRICTED).
 * <p>
 * Wire JSON uses {@code rootOrganizationIds} / {@code organizationIds} (O8-2).
 * Legacy {@code OrgScope} lives in {@code org.legacy} and converts via {@code toOrganizationScope()}.
 * 组织范围：由 Membership + CONTAINS 推导；不读已 DROP 的 map 表。JSON 字段为 organization* 命名。
 */
public record OrganizationScope(
        String mode, List<String> rootOrganizationIds, List<String> organizationIds) {

    public static final String MODE_NONE = "NONE";
    public static final String MODE_UNRESTRICTED = "UNRESTRICTED";
    public static final String MODE_SELF = "SELF";
    public static final String MODE_SELF_AND_DESCENDANTS = "SELF_AND_DESCENDANTS";
    public static final String MODE_EXPLICIT = "EXPLICIT";

    private static final Set<String> KNOWN_MODES = Set.of(
            MODE_NONE, MODE_UNRESTRICTED, MODE_SELF, MODE_SELF_AND_DESCENDANTS, MODE_EXPLICIT);

    public OrganizationScope {
        mode = normalizeMode(mode);
        rootOrganizationIds = List.copyOf(normalizeIds(rootOrganizationIds));
        organizationIds = List.copyOf(normalizeIds(organizationIds));
        if (MODE_NONE.equals(mode) || MODE_UNRESTRICTED.equals(mode)) {
            rootOrganizationIds = List.of();
            organizationIds = List.of();
        }
    }

    public static OrganizationScope none() {
        return new OrganizationScope(MODE_NONE, List.of(), List.of());
    }

    public static OrganizationScope unrestricted() {
        return new OrganizationScope(MODE_UNRESTRICTED, List.of(), List.of());
    }

    public static OrganizationScope self(Collection<String> membershipOrganizationIds) {
        List<String> ids = List.copyOf(normalizeIds(membershipOrganizationIds));
        return new OrganizationScope(MODE_SELF, ids, ids);
    }

    public static OrganizationScope selfAndDescendants(
            Collection<String> roots, Collection<String> expanded) {
        return new OrganizationScope(
                MODE_SELF_AND_DESCENDANTS,
                List.copyOf(normalizeIds(roots)),
                List.copyOf(normalizeIds(expanded)));
    }

    public static OrganizationScope explicit(Collection<String> organizationIds) {
        List<String> ids = List.copyOf(normalizeIds(organizationIds));
        return new OrganizationScope(MODE_EXPLICIT, ids, ids);
    }


    public boolean isNone() {
        return MODE_NONE.equals(mode);
    }

    public boolean isUnrestricted() {
        return MODE_UNRESTRICTED.equals(mode);
    }

    /**
     * Whether {@code organizationId} is inside this scope.
     * UNRESTRICTED → true for any non-blank id; NONE → false; others → membership in organizationIds.
     */
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
            throw new IllegalArgumentException("unknown OrganizationScope mode: " + raw);
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
