package com.subjex.platform.app.workflow;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * WorkflowDefinition — immutable FSM: key, initial state, legal transitions.
 * 不可变 FSM：键、初始状态、合法流转。
 */
public final class WorkflowDefinition {

    private final String definitionKey;
    private final WorkflowState initialState;
    private final List<WorkflowTransition> transitions;

    public WorkflowDefinition(String definitionKey, WorkflowState initialState, List<WorkflowTransition> transitions) {
        this.definitionKey = requireKey(definitionKey);
        this.initialState = Objects.requireNonNull(initialState, "initialState");
        this.transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
        if (this.transitions.isEmpty()) {
            throw new IllegalArgumentException("transitions empty");
        }
    }

    public String definitionKey() {
        return definitionKey;
    }

    public WorkflowState initialState() {
        return initialState;
    }

    public List<WorkflowTransition> transitions() {
        return transitions;
    }

    /**
     * Resolve the edge for current state + action, or empty if illegal.
     * 解析当前状态 + 动作的边；非法则空。
     */
    public Optional<WorkflowTransition> findTransition(WorkflowState from, WorkflowAction action) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(action, "action");
        return transitions.stream()
                .filter(t -> t.from() == from && t.action() == action)
                .findFirst();
    }

    private static String requireKey(String definitionKey) {
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("definitionKey missing");
        }
        return definitionKey.trim();
    }
}
