package com.subjex.platform.app.organization;

/**
 * OrganizationRelation — directed Organization → Organization edge (e.g. CONTAINS).
 * 组织间有向边（如 CONTAINS）。
 */
public record OrganizationRelation(
        String fromOrganizationId, String toOrganizationId, String relationKind, String relationState) {}
