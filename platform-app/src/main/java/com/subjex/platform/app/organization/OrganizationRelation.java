package com.subjex.platform.app.organization;

/**
 * OrganizationRelation — directed Organization → Organization edge (e.g. CONTAINS).
 * Optional {@code sortOrder} orders siblings under the same parent (Should S2).
 * 组织间有向边；sortOrder 表示同父下兄弟序（Should S2）。
 */
public record OrganizationRelation(
        String fromOrganizationId,
        String toOrganizationId,
        String relationKind,
        String relationState,
        int sortOrder) {
    public OrganizationRelation(
            String fromOrganizationId, String toOrganizationId, String relationKind, String relationState) {
        this(fromOrganizationId, toOrganizationId, relationKind, relationState, 0);
    }
}
