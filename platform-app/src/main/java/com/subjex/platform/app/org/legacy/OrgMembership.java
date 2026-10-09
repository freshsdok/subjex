package com.subjex.platform.app.org.legacy;

/**
 * OrgMembership — legacy wire projection of Membership (subject in a tenant-scoped unit).
 * <p>
 * No primary/secondary dual-role in this slice. After O8 / V24 not a physical
 * {@code org_membership} row — projected from ontology {@code membership} + tenant link.
 * <p>
 * 旧线投影：主体在租户内对某组织单元的成员关系。O8/V24 后非物理 org_membership 表行。
 * 本片无主/兼岗双角色。
 */
@Deprecated(since = "O8-3", forRemoval = false)
public record OrgMembership(
        String tenantId, String subjectId, String orgUnitId, String membershipState) {}
