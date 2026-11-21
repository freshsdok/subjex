package com.subjex.platform.app.workflow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * WorkflowDeclarationReader — parse a thin {@code *.workflow.yaml} into a {@link WorkflowDefinition}.
 * 把薄工作流 YAML 解析为定义。
 * <p>
 * This is not a BPM / Camunda compiler: only states, transitions, and clearly marked WP4-2 hooks
 * (auto-start, dual approval, org scope) are accepted. Unknown top-level keys are rejected.
 * 这不是 BPM 编译器：只接受状态、迁移，以及标明的 WP4-2 钩子。未知顶层键拒绝。
 */
public final class WorkflowDeclarationReader {

    private static final Set<String> DOCUMENT_KEYS = Set.of(
            "definitionKey",
            "entityKey",
            "version",
            "initialState",
            "states",
            "transitions",
            "autoStartOnUpsert",
            "orgScope",
            "dualApproval");

    private static final Set<String> TRANSITION_KEYS =
            Set.of("from", "action", "to", "permission", "capabilityId");

    private static final Set<String> ORG_SCOPE_KEYS = Set.of("field", "onActions");

    private static final Set<String> DUAL_APPROVAL_KEYS = Set.of("requestAction", "consumeActions");

    public WorkflowDefinition render(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            throw new WorkflowDeclarationRejected("workflow definition is missing");
        }
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(loaded instanceof Map<?, ?> document)) {
            throw new WorkflowDeclarationRejected("workflow definition must be a mapping");
        }
        rejectUnknown(document, DOCUMENT_KEYS, "workflow");
        String definitionKey = text(required(document, "definitionKey"), "definitionKey");
        String entityKey = optionalText(document, "entityKey");
        int version = optionalVersion(document);
        WorkflowState initial = WorkflowState.of(text(required(document, "initialState"), "initialState"));
        List<WorkflowTransition> transitions = parseTransitions(required(document, "transitions"));
        List<WorkflowState> states = parseStates(document.get("states"));
        if (!states.isEmpty()) {
            if (!states.contains(initial)) {
                throw new WorkflowDeclarationRejected(
                        "initialState is not listed in states: " + initial.wireName());
            }
            for (WorkflowTransition transition : transitions) {
                if (!states.contains(transition.from()) || !states.contains(transition.to())) {
                    throw new WorkflowDeclarationRejected(
                            "transition state is not listed in states: "
                                    + transition.from().wireName()
                                    + " -> "
                                    + transition.to().wireName());
                }
            }
        }
        boolean autoStart = optionalBoolean(document, "autoStartOnUpsert");
        WorkflowOrgScopeDeclaration orgScope = parseOrgScope(document.get("orgScope"));
        WorkflowDualApprovalDeclaration dualApproval = parseDualApproval(document.get("dualApproval"));
        return new WorkflowDefinition(
                definitionKey,
                entityKey,
                version,
                initial,
                states,
                transitions,
                autoStart,
                orgScope,
                dualApproval);
    }

    private static List<WorkflowTransition> parseTransitions(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new WorkflowDeclarationRejected("transitions must be a non-empty list");
        }
        List<WorkflowTransition> transitions = new ArrayList<>();
        Set<String> edges = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                throw new WorkflowDeclarationRejected("transition must be a mapping");
            }
            rejectUnknown(row, TRANSITION_KEYS, "transition");
            WorkflowState from = WorkflowState.of(text(required(row, "from"), "from"));
            WorkflowAction action = WorkflowAction.fromWire(text(required(row, "action"), "action"));
            WorkflowState to = WorkflowState.of(text(required(row, "to"), "to"));
            String permission = text(required(row, "permission"), "permission");
            String capabilityId = optionalText(row, "capabilityId");
            String edgeKey = from.wireName() + "\0" + action.wireName();
            if (!edges.add(edgeKey)) {
                throw new WorkflowDeclarationRejected(
                        "duplicate transition for " + from.wireName() + " + " + action.wireName());
            }
            transitions.add(new WorkflowTransition(from, action, to, permission, capabilityId));
        }
        return transitions;
    }

    private static List<WorkflowState> parseStates(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new WorkflowDeclarationRejected("states must be a non-empty list when present");
        }
        List<WorkflowState> states = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            WorkflowState state = WorkflowState.of(text(item, "states[]"));
            if (!seen.add(state.wireName())) {
                throw new WorkflowDeclarationRejected("duplicate state: " + state.wireName());
            }
            states.add(state);
        }
        return states;
    }

    private static WorkflowOrgScopeDeclaration parseOrgScope(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> row)) {
            throw new WorkflowDeclarationRejected("orgScope must be a mapping");
        }
        rejectUnknown(row, ORG_SCOPE_KEYS, "orgScope");
        return new WorkflowOrgScopeDeclaration(
                text(required(row, "field"), "orgScope.field"), stringList(required(row, "onActions"), "orgScope.onActions"));
    }

    private static WorkflowDualApprovalDeclaration parseDualApproval(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> row)) {
            throw new WorkflowDeclarationRejected("dualApproval must be a mapping");
        }
        rejectUnknown(row, DUAL_APPROVAL_KEYS, "dualApproval");
        return new WorkflowDualApprovalDeclaration(
                text(required(row, "requestAction"), "dualApproval.requestAction"),
                stringList(required(row, "consumeActions"), "dualApproval.consumeActions"));
    }

    private static List<String> stringList(Object raw, String label) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new WorkflowDeclarationRejected(label + " must be a non-empty list");
        }
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            values.add(text(item, label));
        }
        return values;
    }

    private static void rejectUnknown(Map<?, ?> document, Set<String> allowed, String what) {
        for (Object key : document.keySet()) {
            if (!(key instanceof String name) || !allowed.contains(name)) {
                throw new WorkflowDeclarationRejected("unknown " + what + " key: " + key);
            }
        }
    }

    private static Object required(Map<?, ?> document, String key) {
        Object value = document.get(key);
        if (value == null) {
            throw new WorkflowDeclarationRejected(key + " is required");
        }
        return value;
    }

    private static String text(Object raw, String label) {
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new WorkflowDeclarationRejected(label + " must be a non-blank string");
        }
        return value.trim();
    }

    private static String optionalText(Map<?, ?> document, String key) {
        Object raw = document.get(key);
        if (raw == null) {
            return null;
        }
        return text(raw, key);
    }

    private static int optionalVersion(Map<?, ?> document) {
        Object raw = document.get("version");
        if (raw == null) {
            return 1;
        }
        if (raw instanceof Integer number && number >= 1) {
            return number;
        }
        if (raw instanceof Number number && number.intValue() >= 1 && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        throw new WorkflowDeclarationRejected("version must be an integer >= 1");
    }

    private static boolean optionalBoolean(Map<?, ?> document, String key) {
        Object raw = document.get(key);
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        throw new WorkflowDeclarationRejected(key + " must be a boolean");
    }
}
