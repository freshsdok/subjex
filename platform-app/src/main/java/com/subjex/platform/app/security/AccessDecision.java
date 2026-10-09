package com.subjex.platform.app.security;

/**
 * AccessDecision — 一次上下文权限检查的可解释结果。
 * <p>
 * Fields: subject, tenant, orgScope (null = unspecified / no org filter), resource, action,
 * matchedPermission (held permission on allow or after permission passed; null when permission failed),
 * allowed, denyReason (null when allowed).
 * 字段：主体、租户、组织范围（null = 未指定/不过滤）、资源、动作、命中权限、是否允许、拒绝原因。
 */
public record AccessDecision(
        String subjectId,
        String tenantId,
        OrgScope orgScope,
        AccessResource resource,
        AccessAction action,
        String matchedPermission,
        boolean allowed,
        String denyReason) {

    /** Org scope not evaluated — 未评估组织范围。 */
    public static final OrgScope ORG_SCOPE_UNSPECIFIED = null;

    public static final String DENY_PERMISSION_BLANK = "permission_blank";
    public static final String DENY_PERMISSION_MISSING = "permission_missing";
    public static final String DENY_TENANT_MISSING = "tenant_missing";
    public static final String DENY_TENANT_NOT_GRANTED = "tenant_not_granted";
    public static final String DENY_ORG_OUT_OF_SCOPE = "org_out_of_scope";

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
            OrgScope orgScope,
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
            OrgScope orgScope,
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
    public AccessDecision withOrgScope(OrgScope scope) {
        return new AccessDecision(
                subjectId, tenantId, scope, resource, action, matchedPermission, allowed, denyReason);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
