package com.subjex.platform.contract.tenant;

/**
 * TenantRecord — 租户记录：一个租户的标识、名称和状态。
 * <p>
 * The read-only admin console lists these rows. It does not edit them.
 * 只读管理台列出这些行，不修改它们。
 */
public record TenantRecord(String tenantId, String tenantName, TenantState tenantState) {
}
