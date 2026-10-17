package com.subjex.platform.app.workflow;

import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.inbox.InboxRecipient;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.inbox.InboxSendRequest;
import com.subjex.platform.contract.inbox.InboxSeverity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * RepairTicketWorkflowInboxHook — IM-3：报修工单精选流转写入 WORKFLOW 站内信（受信服务端路径）。
 * <p>
 * Curated only: {@code request_approval} → ACTION to org candidates;
 * {@code approve}/{@code reject}/{@code close} → INFO to reporter (+ approval requester).
 * Empty recipients = no-op (never blocks the transition).
 * 仅精选动作；收件人为空则空操作，不打断流转。
 */
public final class RepairTicketWorkflowInboxHook {

    public static final String REF_KIND_WORKFLOW_INSTANCE = "workflow_instance";
    public static final String PRODUCER_KIND = "workflow";

    private final InboxPort inbox;
    /** Null when org gate unavailable (legacy tests) — 无组织门闩时为空（旧测）。 */
    private final RepairTicketOrgApproverGate orgApproverGate;

    public RepairTicketWorkflowInboxHook(InboxPort inbox, RepairTicketOrgApproverGate orgApproverGate) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.orgApproverGate = orgApproverGate;
    }

    /**
     * Fan-out inbox rows for a completed repair-ticket edge (same TX when called from store side-effect).
     * 报修边完成后写扩散站内信（若在 store 副作用中调用则同事务）。
     *
     * @param instance instance before/at transition (ids stable) — 流转时的实例（标识稳定）
     * @param action applied action — 已应用动作
     * @param actorSubjectId who applied the action — 执行人
     * @param approval opened or consumed approval row; may be null — 开立/消费的审批行；可空
     * @param declarationVersion optional WF-RT-4 linkage — 可选声明版本
     * @param at event time — 事件时间
     */
    public void onTransition(
            WorkflowInstance instance,
            WorkflowAction action,
            String actorSubjectId,
            WorkflowApproval approval,
            Integer declarationVersion,
            Instant at) {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actorSubjectId, "actorSubjectId");
        Objects.requireNonNull(at, "at");
        if (!RepairTicketWorkflowDefinitions.REPAIR_TICKET.equals(instance.definitionKey())) {
            return;
        }
        switch (action) {
            case REQUEST_APPROVAL -> notifyApprovalRequested(instance, actorSubjectId, declarationVersion, at);
            case APPROVE -> notifyDecision(
                    instance,
                    actorSubjectId,
                    approval,
                    declarationVersion,
                    at,
                    "报修单已批准",
                    "工单 " + instance.recordId() + " 已批准。",
                    InboxSeverity.INFO);
            case REJECT -> notifyDecision(
                    instance,
                    actorSubjectId,
                    approval,
                    declarationVersion,
                    at,
                    "报修单已驳回",
                    "工单 " + instance.recordId() + " 已驳回，请处理。",
                    InboxSeverity.INFO);
            case CLOSE -> notifyDecision(
                    instance,
                    actorSubjectId,
                    null,
                    declarationVersion,
                    at,
                    "报修单已关闭",
                    "工单 " + instance.recordId() + " 已关闭。",
                    InboxSeverity.INFO);
            default -> {
                // START_WORK and other edges: no curated notice — 其它边不发精选通知
            }
        }
    }

    private void notifyApprovalRequested(
            WorkflowInstance instance, String actorSubjectId, Integer declarationVersion, Instant at) {
        List<String> candidates = orgApproverGate == null
                ? List.of()
                : orgApproverGate.listCandidateSubjectIds(instance);
        Set<String> recipients = new LinkedHashSet<>();
        for (String subjectId : candidates) {
            if (subjectId != null && !subjectId.isBlank() && !subjectId.equals(actorSubjectId)) {
                recipients.add(subjectId.trim());
            }
        }
        sendIfAny(
                instance,
                declarationVersion,
                at,
                "报修单待审批",
                "工单 " + instance.recordId() + " 待您审批。",
                InboxSeverity.ACTION,
                recipients);
    }

    private void notifyDecision(
            WorkflowInstance instance,
            String actorSubjectId,
            WorkflowApproval approval,
            Integer declarationVersion,
            Instant at,
            String title,
            String body,
            InboxSeverity severity) {
        Set<String> recipients = new LinkedHashSet<>();
        addIfOther(recipients, instance.startedBySubjectId(), actorSubjectId);
        if (approval != null) {
            addIfOther(recipients, approval.requestedBySubjectId(), actorSubjectId);
        }
        sendIfAny(instance, declarationVersion, at, title, body, severity, recipients);
    }

    private void sendIfAny(
            WorkflowInstance instance,
            Integer declarationVersion,
            Instant at,
            String title,
            String body,
            InboxSeverity severity,
            Set<String> recipientSubjectIds) {
        if (recipientSubjectIds == null || recipientSubjectIds.isEmpty()) {
            return;
        }
        List<InboxRecipient> recipients = new ArrayList<>(recipientSubjectIds.size());
        for (String subjectId : recipientSubjectIds) {
            recipients.add(new InboxRecipient(InboxRecipientRealm.OPERATOR, subjectId));
        }
        inbox.send(new InboxSendRequest(
                instance.tenantId(),
                InboxChannel.WORKFLOW,
                title,
                body,
                severity,
                PRODUCER_KIND,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                REF_KIND_WORKFLOW_INSTANCE,
                instance.instanceId(),
                instance.entityKey(),
                declarationVersion,
                deepLink(instance.recordId()),
                recipients,
                at));
    }

    private static void addIfOther(Set<String> recipients, String subjectId, String actorSubjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            return;
        }
        String sid = subjectId.trim();
        if (!sid.equals(actorSubjectId)) {
            recipients.add(sid);
        }
    }

    /** Console-relative detail path — 控制台相对详情路径. */
    static String deepLink(String recordId) {
        return "/pages/repair-ticket/" + recordId;
    }
}
