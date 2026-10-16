package com.subjex.platform.app.workflow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcWorkflowStore — persist workflow instances and transition history.
 * 持久化工作流实例与流转历史。
 */
public final class JdbcWorkflowStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcWorkflowStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    public void insertInstance(WorkflowInstance instance, WorkflowHistoryEntry startEntry) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(startEntry, "startEntry");
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update(
                        """
                        INSERT INTO workflow_instance (
                            instance_id, tenant_id, definition_key, entity_key, record_id,
                            current_state, started_by_subject_id, started_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        instance.instanceId(),
                        instance.tenantId(),
                        instance.definitionKey(),
                        instance.entityKey(),
                        instance.recordId(),
                        instance.currentState().name(),
                        instance.startedBySubjectId(),
                        Timestamp.from(instance.startedAt()),
                        Timestamp.from(instance.updatedAt()));
                insertHistory(startEntry);
            });
        } catch (DuplicateKeyException ex) {
            throw new WorkflowAlreadyStartedException(
                    instance.tenantId(), instance.entityKey(), instance.recordId());
        }
    }

    public void applyTransition(
            String instanceId, WorkflowState toState, Instant updatedAt, WorkflowHistoryEntry history) {
        applyTransition(instanceId, toState, updatedAt, history, null);
    }

    /**
     * Apply transition (+ history) and optional in-TX side effect (WF-2 approval rows).
     * 应用流转（+历史）与可选同事务副作用（WF-2 审批行）。
     */
    public void applyTransition(
            String instanceId,
            WorkflowState toState,
            Instant updatedAt,
            WorkflowHistoryEntry history,
            Runnable afterUpdate) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(toState, "toState");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(history, "history");
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update(
                    """
                    UPDATE workflow_instance
                    SET current_state = ?, updated_at = ?
                    WHERE instance_id = ?
                    """,
                    toState.name(),
                    Timestamp.from(updatedAt),
                    instanceId);
            if (updated != 1) {
                throw new WorkflowNotFoundException(instanceId);
            }
            insertHistory(history);
            if (afterUpdate != null) {
                afterUpdate.run();
            }
        });
    }

    /**
     * Cancel prior PENDING approvals and open a new PENDING for this instance — 取消旧 PENDING 并新开一条。
     */
    public WorkflowApproval openApproval(String instanceId, String tenantId, String requestedBy, Instant at) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(at, "at");
        jdbc.update(
                """
                UPDATE workflow_approval
                SET approval_state = ?
                WHERE instance_id = ? AND approval_state = ?
                """,
                WorkflowApproval.CANCELLED,
                instanceId,
                WorkflowApproval.PENDING);
        String id = java.util.UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO workflow_approval (
                    approval_id, instance_id, tenant_id, requested_by_subject_id,
                    approval_state, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                instanceId,
                tenantId,
                requestedBy,
                WorkflowApproval.PENDING,
                Timestamp.from(at));
        return new WorkflowApproval(
                id, instanceId, tenantId, requestedBy, WorkflowApproval.PENDING, at, null, null, null);
    }

    /**
     * Consume PENDING by a different subject; refuse self-approve/reject — 由另一主体消费；拒绝自批。
     */
    public WorkflowApproval consumeApproval(
            String instanceId, String consumerSubjectId, String decisionAction, Instant at) {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(consumerSubjectId, "consumerSubjectId");
        Objects.requireNonNull(decisionAction, "decisionAction");
        Objects.requireNonNull(at, "at");
        WorkflowApproval pending = findPendingApproval(instanceId)
                .orElseThrow(() -> new IllegalStateException(
                        "no PENDING workflow approval for instance " + instanceId));
        if (pending.requestedBySubjectId().equals(consumerSubjectId.trim())) {
            throw new WorkflowNeedsSecondApprover(
                    "workflow approve/reject requires a second operator (requester cannot self-confirm)");
        }
        int updated = jdbc.update(
                """
                UPDATE workflow_approval
                SET approval_state = ?, consumed_at = ?, consumed_by_subject_id = ?, decision_action = ?
                WHERE approval_id = ? AND approval_state = ?
                """,
                WorkflowApproval.CONSUMED,
                Timestamp.from(at),
                consumerSubjectId.trim(),
                decisionAction,
                pending.approvalId(),
                WorkflowApproval.PENDING);
        if (updated != 1) {
            throw new IllegalStateException("workflow approval consume race for " + pending.approvalId());
        }
        return findApproval(pending.approvalId()).orElseThrow();
    }

    public Optional<WorkflowApproval> findPendingApproval(String instanceId) {
        List<WorkflowApproval> rows = jdbc.query(
                """
                SELECT approval_id, instance_id, tenant_id, requested_by_subject_id, approval_state,
                       created_at, consumed_at, consumed_by_subject_id, decision_action
                FROM workflow_approval
                WHERE instance_id = ? AND approval_state = ?
                ORDER BY created_at DESC
                """,
                (rs, i) -> mapApproval(rs),
                instanceId,
                WorkflowApproval.PENDING);
        return rows.stream().findFirst();
    }

    public Optional<WorkflowApproval> findApproval(String approvalId) {
        List<WorkflowApproval> rows = jdbc.query(
                """
                SELECT approval_id, instance_id, tenant_id, requested_by_subject_id, approval_state,
                       created_at, consumed_at, consumed_by_subject_id, decision_action
                FROM workflow_approval
                WHERE approval_id = ?
                """,
                (rs, i) -> mapApproval(rs),
                approvalId);
        return rows.stream().findFirst();
    }

    private static WorkflowApproval mapApproval(ResultSet rs) throws SQLException {
        return new WorkflowApproval(
                rs.getString("approval_id"),
                rs.getString("instance_id"),
                rs.getString("tenant_id"),
                rs.getString("requested_by_subject_id"),
                rs.getString("approval_state"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                rs.getString("consumed_by_subject_id"),
                rs.getString("decision_action"));
    }

    public Optional<WorkflowInstance> findById(String instanceId) {
        List<WorkflowInstance> rows = jdbc.query(
                """
                SELECT instance_id, tenant_id, definition_key, entity_key, record_id,
                       current_state, started_by_subject_id, started_at, updated_at
                FROM workflow_instance
                WHERE instance_id = ?
                """,
                (rs, i) -> mapInstance(rs),
                instanceId);
        return rows.stream().findFirst();
    }

    public Optional<WorkflowInstance> findByRecord(String tenantId, String entityKey, String recordId) {
        List<WorkflowInstance> rows = jdbc.query(
                """
                SELECT instance_id, tenant_id, definition_key, entity_key, record_id,
                       current_state, started_by_subject_id, started_at, updated_at
                FROM workflow_instance
                WHERE tenant_id = ? AND entity_key = ? AND record_id = ?
                """,
                (rs, i) -> mapInstance(rs),
                tenantId,
                entityKey,
                recordId);
        return rows.stream().findFirst();
    }

    public List<WorkflowHistoryEntry> listHistory(String instanceId) {
        return jdbc.query(
                """
                SELECT history_id, instance_id, tenant_id, from_state, action_name, to_state,
                       actor_subject_id, note_text, capability_id, created_at
                FROM workflow_transition_history
                WHERE instance_id = ?
                ORDER BY created_at ASC, history_id ASC
                """,
                (rs, i) -> mapHistory(rs),
                instanceId);
    }

    private void insertHistory(WorkflowHistoryEntry entry) {
        jdbc.update(
                """
                INSERT INTO workflow_transition_history (
                    history_id, instance_id, tenant_id, from_state, action_name, to_state,
                    actor_subject_id, note_text, capability_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.historyId(),
                entry.instanceId(),
                entry.tenantId(),
                entry.fromState() == null ? null : entry.fromState().name(),
                entry.actionName(),
                entry.toState().name(),
                entry.actorSubjectId(),
                entry.noteText(),
                entry.capabilityId(),
                Timestamp.from(entry.createdAt()));
    }

    private static WorkflowInstance mapInstance(ResultSet rs) throws SQLException {
        return new WorkflowInstance(
                rs.getString("instance_id"),
                rs.getString("tenant_id"),
                rs.getString("definition_key"),
                rs.getString("entity_key"),
                rs.getString("record_id"),
                WorkflowState.valueOf(rs.getString("current_state")),
                rs.getString("started_by_subject_id"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static WorkflowHistoryEntry mapHistory(ResultSet rs) throws SQLException {
        String from = rs.getString("from_state");
        return new WorkflowHistoryEntry(
                rs.getString("history_id"),
                rs.getString("instance_id"),
                rs.getString("tenant_id"),
                from == null ? null : WorkflowState.valueOf(from),
                rs.getString("action_name"),
                WorkflowState.valueOf(rs.getString("to_state")),
                rs.getString("actor_subject_id"),
                rs.getString("note_text"),
                rs.getString("capability_id"),
                rs.getTimestamp("created_at").toInstant());
    }
}
