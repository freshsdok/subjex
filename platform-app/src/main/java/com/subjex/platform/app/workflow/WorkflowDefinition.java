package com.subjex.platform.app.workflow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * WorkflowDefinition — immutable FSM: key, initial state, legal transitions.
 * 不可变 FSM：键、初始状态、合法流转。
 * <p>
 * Loaded from {@code workflows/*.workflow.yaml}. Auto-start, dual-approval, and org-scope fields are
 * applied by the engine (WP4-2).
 * 从 classpath 声明加载。自动启动、双人审批、组织范围由引擎应用。
 */
public final class WorkflowDefinition {

    private final String definitionKey;
    private final String entityKey;
    private final int version;
    private final WorkflowState initialState;
    private final List<WorkflowState> states;
    private final List<WorkflowTransition> transitions;
    private final boolean autoStartOnUpsert;
    private final WorkflowOrgScopeDeclaration orgScope;
    private final WorkflowDualApprovalDeclaration dualApproval;

    public WorkflowDefinition(String definitionKey, WorkflowState initialState, List<WorkflowTransition> transitions) {
        this(definitionKey, null, 1, initialState, List.of(), transitions, false, null, null);
    }

    public WorkflowDefinition(
            String definitionKey,
            String entityKey,
            int version,
            WorkflowState initialState,
            List<WorkflowState> states,
            List<WorkflowTransition> transitions,
            boolean autoStartOnUpsert,
            WorkflowOrgScopeDeclaration orgScope,
            WorkflowDualApprovalDeclaration dualApproval) {
        this.definitionKey = requireKey(definitionKey);
        this.entityKey = entityKey == null || entityKey.isBlank() ? null : entityKey.trim();
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        this.version = version;
        this.initialState = Objects.requireNonNull(initialState, "initialState");
        this.transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
        if (this.transitions.isEmpty()) {
            throw new IllegalArgumentException("transitions empty");
        }
        this.states = states == null || states.isEmpty() ? derivedStates(this.initialState, this.transitions) : List.copyOf(states);
        this.autoStartOnUpsert = autoStartOnUpsert;
        this.orgScope = orgScope;
        this.dualApproval = dualApproval;
    }

    public String definitionKey() {
        return definitionKey;
    }

    /** Bound entity key when declared — 声明中绑定的实体键（可空）。 */
    public String entityKey() {
        return entityKey;
    }

    public int version() {
        return version;
    }

    public WorkflowState initialState() {
        return initialState;
    }

    public List<WorkflowState> states() {
        return states;
    }

    public List<WorkflowTransition> transitions() {
        return transitions;
    }

    /**
     * Declared auto-start flag, applied after entity upsert (WP4-2).
     * 声明的自动启动标志，实体 upsert 后由引擎应用。
     */
    public boolean autoStartOnUpsert() {
        return autoStartOnUpsert;
    }

    /**
     * Declared org-scope hook, applied by {@link WorkflowOrgApproverGate}.
     * 声明的组织范围钩子，由组织审批门闩应用。
     */
    public Optional<WorkflowOrgScopeDeclaration> orgScope() {
        return Optional.ofNullable(orgScope);
    }

    /**
     * Declared dual-approval hook, applied by {@link WorkflowService}.
     * 声明的双人审批钩子，由工作流服务应用。
     */
    public Optional<WorkflowDualApprovalDeclaration> dualApproval() {
        return Optional.ofNullable(dualApproval);
    }

    /**
     * Resolve the edge for current state + action, or empty if illegal.
     * 解析当前状态 + 动作的边；非法则空。
     */
    public Optional<WorkflowTransition> findTransition(WorkflowState from, WorkflowAction action) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(action, "action");
        return transitions.stream()
                .filter(t -> t.from().equals(from) && t.action().equals(action))
                .findFirst();
    }

    private static List<WorkflowState> derivedStates(WorkflowState initial, List<WorkflowTransition> transitions) {
        java.util.LinkedHashSet<WorkflowState> names = new java.util.LinkedHashSet<>();
        names.add(initial);
        for (WorkflowTransition transition : transitions) {
            names.add(transition.from());
            names.add(transition.to());
        }
        return List.copyOf(names);
    }

    private static String requireKey(String definitionKey) {
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("definitionKey missing");
        }
        return definitionKey.trim();
    }
}
