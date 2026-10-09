package com.subjex.platform.app.org.legacy;

/**
 * OrgUnit — legacy wire projection of an Organization (department/team) within a tenant.
 * <p>
 * Tree is scoped by {@code tenantId}. {@code parentOrgUnitId} null means root.
 * After O8 / V24 this is not a physical {@code org_unit} table row — {@link JdbcOrgDirectory}
 * projects from the ontology. Never use a tenant row as a department.
 * <p>
 * 旧线投影：租户内组织单元（部门/团队）。O8/V24 后不是物理 org_unit 表行，由目录从本体投影。
 * 树按 tenantId 隔离；parentOrgUnitId 为空即根。不用租户行冒充部门。
 */
@Deprecated(since = "O8-3", forRemoval = false)
public record OrgUnit(
        String tenantId, String orgUnitId, String parentOrgUnitId, String unitName, String unitState) {}
