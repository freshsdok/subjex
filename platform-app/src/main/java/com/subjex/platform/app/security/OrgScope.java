package com.subjex.platform.app.security;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * OrgScope — 组织范围：本部门及下级（唯一首版模式）。
 * <p>
 * {@code rootUnitIds} are the caller's ACTIVE membership units; {@code unitIds} is the expanded
 * closure (roots + descendants). Null on {@link AccessDecision} means unspecified (no filter).
 * {@code rootUnitIds} 为调用方 ACTIVE 成员单元；{@code unitIds} 为展开闭包。决策上为 null 表示未指定（不过滤）。
 */
public record OrgScope(String mode, List<String> rootUnitIds, List<String> unitIds) {

    /** Sole first-wave mode — 首版唯一模式。 */
    public static final String MODE_SELF_AND_DESCENDANTS = "SELF_AND_DESCENDANTS";

    public OrgScope {
        mode = mode == null || mode.isBlank() ? MODE_SELF_AND_DESCENDANTS : mode.trim();
        rootUnitIds = List.copyOf(normalizeIds(rootUnitIds));
        unitIds = List.copyOf(normalizeIds(unitIds));
    }

    public static OrgScope selfAndDescendants(Collection<String> roots, Collection<String> expanded) {
        return new OrgScope(MODE_SELF_AND_DESCENDANTS, List.copyOf(normalizeIds(roots)), List.copyOf(normalizeIds(expanded)));
    }

    public boolean contains(String orgUnitId) {
        if (orgUnitId == null || orgUnitId.isBlank()) {
            return false;
        }
        return unitIds.contains(orgUnitId.trim());
    }

    /**
     * Short summary for logs / flat displays — 日志与扁平展示用短摘要。
     * Example: {@code SELF_AND_DESCENDANTS roots=[u-eng] units=[u-eng,u-team]}.
     */
    public String summary() {
        return mode + " roots=" + rootUnitIds + " units=" + unitIds;
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
