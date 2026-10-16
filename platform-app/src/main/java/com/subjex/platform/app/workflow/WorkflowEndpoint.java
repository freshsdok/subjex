package com.subjex.platform.app.workflow;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * WorkflowEndpoint — WF-1 start / transition / status for repair-ticket (and keyed definitions).
 * WF-1：报修工单（及具名定义）的启动 / 流转 / 状态。
 * <p>
 * Paths under {@code /api/v1/workflows/**}; tenant grant via service requireGranted.
 * 路径在 {@code /api/v1/workflows/**}；租户授权由服务内 requireGranted 核对。
 */
@RestController
public class WorkflowEndpoint {

    public static final String BASE = JsonApi.BASE + "/workflows";

    private final WorkflowService workflows;

    public WorkflowEndpoint(WorkflowService workflows) {
        this.workflows = Objects.requireNonNull(workflows, "workflows");
    }

    @PostMapping(BASE + "/{definitionKey}/instances")
    public InstanceDocument start(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("definitionKey") String definitionKey,
            @RequestBody StartRequest body) {
        requireOperator(operator);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        WorkflowInstance started =
                workflows.start(operator, definitionKey, body.tenantId(), body.entityKey(), body.recordId());
        return InstanceDocument.from(started);
    }

    @PostMapping(BASE + "/instances/{instanceId}/transitions")
    public InstanceDocument transition(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("instanceId") String instanceId,
            @RequestBody TransitionRequest body) {
        requireOperator(operator);
        if (body == null || body.action() == null || body.action().isBlank()) {
            throw new IllegalArgumentException("action required");
        }
        WorkflowInstance updated =
                workflows.transition(operator, instanceId, WorkflowAction.fromWire(body.action()), body.note());
        return InstanceDocument.from(updated);
    }

    @GetMapping(BASE + "/instances/{instanceId}")
    public StatusDocument getStatus(
            @AuthenticationPrincipal OperatorPrincipal operator, @PathVariable("instanceId") String instanceId) {
        requireOperator(operator);
        return StatusDocument.from(workflows.status(operator, instanceId));
    }

    @GetMapping(BASE + "/by-record")
    public StatusDocument byRecord(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam("tenantId") String tenantId,
            @RequestParam("entityKey") String entityKey,
            @RequestParam("recordId") String recordId) {
        requireOperator(operator);
        return StatusDocument.from(workflows.statusByRecord(operator, tenantId, entityKey, recordId));
    }

    private static void requireOperator(OperatorPrincipal operator) {
        if (operator == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    public record StartRequest(String tenantId, String entityKey, String recordId) {}

    public record TransitionRequest(String action, String note) {}

    public record InstanceDocument(
            String instanceId,
            String tenantId,
            String definitionKey,
            String entityKey,
            String recordId,
            String currentState,
            String startedBySubjectId,
            Instant startedAt,
            Instant updatedAt) {
        static InstanceDocument from(WorkflowInstance i) {
            return new InstanceDocument(
                    i.instanceId(),
                    i.tenantId(),
                    i.definitionKey(),
                    i.entityKey(),
                    i.recordId(),
                    i.currentState().name(),
                    i.startedBySubjectId(),
                    i.startedAt(),
                    i.updatedAt());
        }
    }

    public record HistoryDocument(
            String historyId,
            String fromState,
            String actionName,
            String toState,
            String actorSubjectId,
            String noteText,
            String capabilityId,
            Instant createdAt) {
        static HistoryDocument from(WorkflowHistoryEntry e) {
            return new HistoryDocument(
                    e.historyId(),
                    e.fromState() == null ? null : e.fromState().name(),
                    e.actionName(),
                    e.toState().name(),
                    e.actorSubjectId(),
                    e.noteText(),
                    e.capabilityId(),
                    e.createdAt());
        }
    }

    public record PendingApprovalDocument(
            String approvalId, String requestedBySubjectId, String approvalState, Instant createdAt) {
        static PendingApprovalDocument from(WorkflowApproval a) {
            if (a == null) {
                return null;
            }
            return new PendingApprovalDocument(
                    a.approvalId(), a.requestedBySubjectId(), a.approvalState(), a.createdAt());
        }
    }

    public record StatusDocument(
            InstanceDocument instance, List<HistoryDocument> history, PendingApprovalDocument pendingApproval) {
        static StatusDocument from(WorkflowService.WorkflowStatusView view) {
            return new StatusDocument(
                    InstanceDocument.from(view.instance()),
                    view.history().stream().map(HistoryDocument::from).toList(),
                    PendingApprovalDocument.from(view.pendingApproval()));
        }
    }
}
