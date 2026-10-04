/**
 * Tenant — 租户：隔离边界。请求里没有租户，就拒绝，而不是落到一个默认租户。
 * <p>
 * Fail closed: a missing tenant is denied. An empty context must not inherit another tenant's rows.
 * 失败即关闭：租户缺失就拒绝。空上下文不得继承别的租户的行。
 */
package com.subjex.platform.contract.tenant;
