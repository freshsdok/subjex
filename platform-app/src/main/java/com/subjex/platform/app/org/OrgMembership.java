package com.subjex.platform.app.org;

/**
 * OrgMembership — 主体在租户内对某组织单元的简单成员关系。
 * <p>
 * No primary/secondary dual-role in this slice.
 * 本片无主/兼岗双角色。
 */
public record OrgMembership(
        String tenantId, String subjectId, String orgUnitId, String membershipState) {}
