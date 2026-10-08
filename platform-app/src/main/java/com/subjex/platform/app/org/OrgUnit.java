package com.subjex.platform.app.org;

/**
 * OrgUnit — 租户内组织单元（部门/团队）一行。
 * <p>
 * Tree is scoped by {@code tenantId}. {@code parentOrgUnitId} null means root.
 * Never use a tenant row as a department.
 * 树按 {@code tenantId} 隔离。{@code parentOrgUnitId} 为空即根。
 * 不用租户行冒充部门。
 */
public record OrgUnit(
        String tenantId, String orgUnitId, String parentOrgUnitId, String unitName, String unitState) {}
