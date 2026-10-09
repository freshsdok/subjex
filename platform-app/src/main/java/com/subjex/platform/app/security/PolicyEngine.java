package com.subjex.platform.app.security;

/**
 * PolicyEngine — 可外置策略端口（Cedar / Casbin 子集形状）。
 * <p>
 * Input shape maps to:
 * <ul>
 *   <li><b>Cedar:</b> principal → {@link PolicyPrincipal}; action → {@link AccessAction} +
 *       {@code requiredPermission}; resource → {@link PolicyResource}; context → {@link PolicyContext}</li>
 *   <li><b>Casbin:</b> sub → subjectId (+ permissions as roles/g); obj → resource kind/id;
 *       act → requiredPermission (action label stays explainability); domain → tenantId</li>
 * </ul>
 * First adapter is {@link SqlRbacPolicyEngine} over {@link AccessChecker}. No custom DSL.
 * Default wiring: {@link CedarPolicyEngine} via {@code platform.authz.engine=cedar} (AuthZ-1d).
 * {@link SqlRbacPolicyEngine} selectable with {@code sql}. No self-authored DSL.
 * 默认 Cedar；可选 sql；无自研 DSL。
 */
public interface PolicyEngine {

    /**
     * Evaluate without throwing — 判定且不抛。
     *
     * @param requiredPermission platform permission name (SQL RBAC / Casbin act subset)
     */
    AccessDecision evaluate(
            PolicyPrincipal principal,
            String requiredPermission,
            AccessAction action,
            PolicyResource resource,
            PolicyContext context);

    /**
     * Fail-closed: deny → {@link AccessDecisionDeniedException} — 拒绝则抛带决策的异常。
     */
    default AccessDecision require(
            PolicyPrincipal principal,
            String requiredPermission,
            AccessAction action,
            PolicyResource resource,
            PolicyContext context) {
        AccessDecision decision = evaluate(principal, requiredPermission, action, resource, context);
        if (!decision.allowed()) {
            throw new AccessDecisionDeniedException(
                    decision,
                    requiredPermission == null || requiredPermission.isBlank() ? "permission" : requiredPermission);
        }
        return decision;
    }
}
