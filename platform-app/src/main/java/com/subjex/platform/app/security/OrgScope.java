package com.subjex.platform.app.security;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * OrgScope — organization scope for authorization (O4).
 * <p>
 * Modes: {@link #MODE_NONE}, {@link #MODE_UNRESTRICTED}, {@link #MODE_SELF},
 * {@link #MODE_SELF_AND_DESCENDANTS}, {@link #MODE_EXPLICIT}. Derived at Context time from
 * Membership + OrganizationRelation (or explicit grant) — never stored on Membership.
 * Null on {@link AccessDecision} / {@link PolicyContext} means <em>unspecified</em>;
 * {@link AccessChecker} treats unspecified + resource org id as fail-closed (not UNRESTRICTED).
 * <p>
 * 组织范围（O4）。由 Membership + OrganizationRelation（或显式授予）在 Context 推导；
 * 决策/上下文上 null = 未指定；带资源组织 id 时 fail-closed，不等于 UNRESTRICTED。
 */
public record OrgScope(String mode, List<String> rootUnitIds, List<String> unitIds) {

    public static final String MODE_NONE = "NONE";
    public static final String MODE_UNRESTRICTED = "UNRESTRICTED";
    public static final String MODE_SELF = "SELF";
    public static final String MODE_SELF_AND_DESCENDANTS = "SELF_AND_DESCENDANTS";
    public static final String MODE_EXPLICIT = "EXPLICIT";

    private static final Set<String> KNOWN_MODES = Set.of(
            MODE_NONE, MODE_UNRESTRICTED, MODE_SELF, MODE_SELF_AND_DESCENDANTS, MODE_EXPLICIT);

    public OrgScope {
        mode = normalizeMode(mode);
        rootUnitIds = List.copyOf(normalizeIds(rootUnitIds));
        unitIds = List.copyOf(normalizeIds(unitIds));
        if (MODE_NONE.equals(mode) || MODE_UNRESTRICTED.equals(mode)) {
            rootUnitIds = List.of();
            unitIds = List.of();
        }
    }

    /** Explicit empty scope — deny org-scoped resources. */
    public static OrgScope none() {
        return new OrgScope(MODE_NONE, List.of(), List.of());
    }

    /** Explicit platform / break-glass grant — never implied by missing data. */
    public static OrgScope unrestricted() {
        return new OrgScope(MODE_UNRESTRICTED, List.of(), List.of());
    }

    /** Only organizations where the subject has ACTIVE Membership. */
    public static OrgScope self(Collection<String> membershipOrgIds) {
        List<String> ids = List.copyOf(normalizeIds(membershipOrgIds));
        return new OrgScope(MODE_SELF, ids, ids);
    }

    /** SELF plus CONTAINS closure. */
    public static OrgScope selfAndDescendants(Collection<String> roots, Collection<String> expanded) {
        return new OrgScope(
                MODE_SELF_AND_DESCENDANTS,
                List.copyOf(normalizeIds(roots)),
                List.copyOf(normalizeIds(expanded)));
    }

    /** Caller- or policy-supplied organization id set. */
    public static OrgScope explicit(Collection<String> organizationIds) {
        List<String> ids = List.copyOf(normalizeIds(organizationIds));
        return new OrgScope(MODE_EXPLICIT, ids, ids);
    }

    public boolean isNone() {
        return MODE_NONE.equals(mode);
    }

    public boolean isUnrestricted() {
        return MODE_UNRESTRICTED.equals(mode);
    }

    /**
     * Whether {@code orgUnitId} / organization id is inside this scope.
     * UNRESTRICTED → true for any non-blank id; NONE → false; others → membership in unitIds.
     */
    public boolean contains(String orgUnitId) {
        if (orgUnitId == null || orgUnitId.isBlank()) {
            return false;
        }
        if (isUnrestricted()) {
            return true;
        }
        if (isNone()) {
            return false;
        }
        return unitIds.contains(orgUnitId.trim());
    }

    /**
     * Short summary for logs / flat displays.
     * Example: {@code SELF_AND_DESCENDANTS roots=[u-eng] units=[u-eng,u-team]}.
     */
    public String summary() {
        if (isNone() || isUnrestricted()) {
            return mode;
        }
        return mode + " roots=" + rootUnitIds + " units=" + unitIds;
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
