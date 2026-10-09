package com.subjex.platform.app.security;

/**
 * AccessDecision — 一次上下文权限检查的可解释结果。
 * <p>
 * Fields: subject, tenant, orgScope (null = unspecified — fail-closed when a resource org id is
 * checked), resource, action, matchedPermission, allowed, denyReason.
 * 字段：主体、租户、组织范围（null = 未指定；带资源组织 id 时 fail-closed）、资源、动作、命中权限、是否允许、拒绝原因。
 */
public record AccessDecision(
        String subjectId,
        String tenantId,
        OrganizationScope orgScope,
        AccessResource resource,
        AccessAction action,
        String matchedPermission,
        boolean allowed,
        String denyReason) {

    /** Org scope not evaluated / unspecified — 未评估组织范围。 */
    public static final OrganizationScope ORG_SCOPE_UNSPECIFIED = null;

    public static final String DENY_PERMISSION_BLANK = "permission_blank";
    public static final String DENY_PERMISSION_MISSING = "permission_missing";
    public static final String DENY_TENANT_MISSING = "tenant_missing";
    public static final String DENY_TENANT_NOT_GRANTED = "tenant_not_granted";
    public static final String DENY_TENANT_MISMATCH = "tenant_mismatch";
    public static final String DENY_ORG_OUT_OF_SCOPE = "org_out_of_scope";
    public static final String DENY_ORG_SCOPE_MISSING = "org_scope_missing";
    /** Cedar native/FFI unavailable or policy load failed — Cedar 原生库不可用或策略加载失败。 */
    public static final String DENY_CEDAR_UNAVAILABLE = "cedar_unavailable";
    /** Cedar engine error during evaluate — Cedar 判定过程出错。 */
    public static final String DENY_CEDAR_ERROR = "cedar_error";

    public static AccessDecision allow(
            String subjectId,
            String tenantId,
            AccessResource resource,
            AccessAction action,
            String matchedPermission) {
        return allow(subjectId, tenantId, ORG_SCOPE_UNSPECIFIED, resource, action, matchedPermission);
    }

    public static AccessDecision allow(
            String subjectId,
            String tenantId,
            OrganizationScope orgScope,
            AccessResource resource,
            AccessAction action,
            String matchedPermission) {
        return new AccessDecision(
                subjectId,
                blankToNull(tenantId),
                orgScope,
                resource,
                action,
                matchedPermission,
                true,
                null);
    }

    public static AccessDecision deny(
            String subjectId,
            String tenantId,
            AccessResource resource,
            AccessAction action,
            String matchedPermission,
            String denyReason) {
        return deny(subjectId, tenantId, ORG_SCOPE_UNSPECIFIED, resource, action, matchedPermission, denyReason);
    }

    public static AccessDecision deny(
            String subjectId,
            String tenantId,
            OrganizationScope orgScope,
            AccessResource resource,
            AccessAction action,
            String matchedPermission,
            String denyReason) {
        return new AccessDecision(
                subjectId,
                blankToNull(tenantId),
                orgScope,
                resource,
                action,
                matchedPermission,
                false,
                denyReason);
    }



    /** Copy with org scope attached (keeps allow/deny) — 附带组织范围的副本。 */
    public AccessDecision withOrgScope(OrganizationScope scope) {
        return new AccessDecision(
                subjectId, tenantId, scope, resource, action, matchedPermission, allowed, denyReason);
    }


    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
