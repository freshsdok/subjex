package com.subjex.platform.app.workflow;

import java.util.List;
import java.util.Objects;

/**
 * WorkflowOrgScopeDeclaration — declared org-scope hook, applied by {@link WorkflowOrgApproverGate}.
 * 声明中的组织范围钩子，由组织审批门闩应用。
 */
public record WorkflowOrgScopeDeclaration(String field, List<String> onActions) {

    public WorkflowOrgScopeDeclaration {
        field = requireToken("orgScope.field", field);
        onActions = List.copyOf(Objects.requireNonNull(onActions, "orgScope.onActions"));
        if (onActions.isEmpty()) {
            throw new IllegalArgumentException("orgScope.onActions empty");
        }
        onActions.forEach(action -> requireToken("orgScope.onActions", action));
    }

    private static String requireToken(String label, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " is missing");
        }
        return raw.trim();
    }
}
