package com.subjex.platform.app.inbox;

import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxEntryView;
import com.subjex.platform.contract.inbox.InboxListQuery;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.inbox.InboxRecipient;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.inbox.InboxSendRequest;
import com.subjex.platform.contract.inbox.InboxSendResult;
import com.subjex.platform.contract.inbox.InboxSeverity;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/**
 * InboxService — IM-2：操作员站内信用例（租户授权 + 本人 OPERATOR 收件箱 + 系统发送）。
 * <p>
 * Console path uses {@link InboxRecipientRealm#OPERATOR} only; END_USER waits for IM-6.
 * Cross-subject read/mark → 403; missing entry in tenant → 404.
 * 控制台路径只用 OPERATOR；跨主体读/已读 → 403；租户内无此条 → 404。
 */
public final class InboxService {

    private final InboxPort inbox;
    private final OperatorTenantAccess tenantAccess;
    private final TenantGuard tenantGuard;
    private final Clock clock;

    public InboxService(
            InboxPort inbox, OperatorTenantAccess tenantAccess, TenantGuard tenantGuard, Clock clock) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** List own OPERATOR entries — 列本人操作员收件. */
    public List<InboxEntryView> listOwn(
            OperatorPrincipal operator,
            String tenantId,
            boolean unreadOnly,
            InboxChannel channel,
            int limit,
            int offset) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_READ.permissionName());
        return inbox.listEntries(
                tid,
                InboxRecipientRealm.OPERATOR,
                operator.subjectId(),
                new InboxListQuery(unreadOnly, channel, limit, offset));
    }

    /** Unread badge count — 未读角标计数. */
    public long unreadCount(OperatorPrincipal operator, String tenantId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_READ.permissionName());
        return inbox.unreadCount(tid, InboxRecipientRealm.OPERATOR, operator.subjectId());
    }

    /** Own entry detail — 本人详情. */
    public InboxEntryView getOwn(OperatorPrincipal operator, String tenantId, String entryId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_READ.permissionName());
        return requireOwnEntry(operator, tid, entryId);
    }

    /** Mark one own entry read (idempotent) — 标记本人一条已读（幂等）. */
    public void markRead(OperatorPrincipal operator, String tenantId, String entryId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_READ.permissionName());
        requireOwnEntry(operator, tid, entryId);
        inbox.markRead(
                tid, InboxRecipientRealm.OPERATOR, operator.subjectId(), entryId, clock.instant());
    }

    /**
     * Mark many own entries read; any foreign → 403, any missing → 404.
     * 批量已读；任一条属他人 → 403，任一条不存在 → 404。
     */
    public int markReadBatch(OperatorPrincipal operator, String tenantId, List<String> entryIds) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_READ.permissionName());
        if (entryIds == null || entryIds.isEmpty()) {
            throw new IllegalArgumentException("entryIds must not be empty");
        }
        Instant at = clock.instant();
        int marked = 0;
        for (String raw : entryIds) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("entryIds must not contain blank ids");
            }
            String entryId = raw.trim();
            requireOwnEntry(operator, tid, entryId);
            if (inbox.markRead(tid, InboxRecipientRealm.OPERATOR, operator.subjectId(), entryId, at)) {
                marked++;
            }
        }
        return marked;
    }

    /**
     * Send SYSTEM notice to OPERATOR recipients in one tenant (locked decision 1: one message per tenant).
     * 向本租户操作员收件人发 SYSTEM 通知（锁定决策 1：每租户一条消息）。
     */
    public InboxSendResult sendSystem(
            OperatorPrincipal operator,
            String tenantId,
            String title,
            String body,
            InboxSeverity severity,
            List<String> recipientSubjectIds,
            String deepLink) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.INBOX_SEND_SYSTEM.permissionName());
        if (recipientSubjectIds == null || recipientSubjectIds.isEmpty()) {
            throw new IllegalArgumentException("recipientSubjectIds must not be empty");
        }
        InboxSeverity sev = severity == null ? InboxSeverity.INFO : severity;
        List<InboxRecipient> recipients = new ArrayList<>(recipientSubjectIds.size());
        for (String subjectId : recipientSubjectIds) {
            if (subjectId == null || subjectId.isBlank()) {
                throw new IllegalArgumentException("recipientSubjectIds must not contain blank ids");
            }
            recipients.add(new InboxRecipient(InboxRecipientRealm.OPERATOR, subjectId.trim()));
        }
        return inbox.send(new InboxSendRequest(
                tid,
                InboxChannel.SYSTEM,
                title,
                body,
                sev,
                "system",
                operator.subjectId(),
                null,
                null,
                null,
                null,
                blankToNull(deepLink),
                recipients,
                clock.instant()));
    }

    private InboxEntryView requireOwnEntry(OperatorPrincipal operator, String tenantId, String entryId) {
        InboxEntryView view = inbox.findEntryInTenant(tenantId, entryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "inbox entry not found"));
        if (view.entry().recipientRealm() != InboxRecipientRealm.OPERATOR
                || !view.entry().recipientSubjectId().equals(operator.subjectId())) {
            throw new AccessDeniedException("inbox entry belongs to another recipient");
        }
        return view;
    }

    private String requireTenantAndGrant(OperatorPrincipal operator, String tenantId) {
        Objects.requireNonNull(operator, "operator");
        // Missing/blank tenant → TenantMissingException → 403 (fail-closed).
        // 缺租户 → TenantMissingException → 403（失败关闭）。
        tenantGuard.requireTenant(tenantId);
        String tid = tenantId.trim();
        tenantAccess.requireGranted(operator, tid);
        return tid;
    }

    private static void requirePermission(OperatorPrincipal operator, String permission) {
        if (!operator.permissionNames().contains(permission)) {
            throw new AccessDeniedException("missing permission " + permission);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
