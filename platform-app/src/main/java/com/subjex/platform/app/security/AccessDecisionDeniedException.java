package com.subjex.platform.app.security;

import org.springframework.security.access.AccessDeniedException;

/**
 * AccessDecisionDeniedException — 带完整 {@link AccessDecision} 的拒绝，供 HTTP/审计原样写出。
 */
public final class AccessDecisionDeniedException extends AccessDeniedException {

    private final AccessDecision decision;
    /** Declared / required permission name for backward-compatible {@code permission} field. */
    private final String requiredPermission;

    public AccessDecisionDeniedException(AccessDecision decision, String requiredPermission) {
        super(messageFor(decision, requiredPermission));
        this.decision = decision;
        this.requiredPermission = requiredPermission == null || requiredPermission.isBlank()
                ? "permission"
                : requiredPermission;
    }

    public AccessDecision decision() {
        return decision;
    }

    public String requiredPermission() {
        return requiredPermission;
    }

    private static String messageFor(AccessDecision decision, String requiredPermission) {
        String reason = decision == null || decision.denyReason() == null ? "denied" : decision.denyReason();
        String perm = requiredPermission == null || requiredPermission.isBlank() ? "permission" : requiredPermission;
        return perm + " required (" + reason + ")";
    }
}
