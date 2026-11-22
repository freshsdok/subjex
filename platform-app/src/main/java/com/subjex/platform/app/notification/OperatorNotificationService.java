package com.subjex.platform.app.notification;

import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.app.security.PolicyContext;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyPrincipal;
import com.subjex.platform.app.security.PolicyResource;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;

/**
 * OperatorNotificationService — read-only operator query of notification send records in a granted tenant.
 * 操作员在已授权租户内只读查询外部通知发送记录；不能发送、重试或重放。
 */
public final class OperatorNotificationService {

    private final JdbcNotificationSendQuery sends;
    private final OperatorTenantAccess tenantAccess;
    private final PolicyEngine policyEngine;

    public OperatorNotificationService(
            JdbcNotificationSendQuery sends, OperatorTenantAccess tenantAccess, PolicyEngine policyEngine) {
        this.sends = Objects.requireNonNull(sends, "sends");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
    }

    /**
     * Tenant-scoped list, newest first, with optional status, channel, recipient, and time-window filters.
     * 当前租户内列表，按时间从新到旧；可按状态、渠道、收件人和时间窗筛选。
     */
    public List<NotificationSendDocument> list(
            OperatorPrincipal operator,
            String tenantId,
            String status,
            String channel,
            String recipient,
            Instant from,
            Instant to,
            Integer limit) {
        String tid = requireOperatorAccess(operator, tenantId);
        return sends.list(tid, parseStatus(status), parseChannel(channel), recipient, from, to, limit);
    }

    /**
     * One send record in the granted tenant.
     * 在已授权租户内查看一条发送记录。
     */
    public NotificationSendDocument get(OperatorPrincipal operator, String tenantId, String eventId) {
        String tid = requireOperatorAccess(operator, tenantId);
        return sends.get(tid, eventId);
    }

    private String requireOperatorAccess(OperatorPrincipal operator, String tenantId) {
        // Reserved platform is not a business notification tenant; fail closed as 403 for operators.
        // 保留租户 platform 不是业务通知租户；操作员侧失败关闭为 403。
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String tid = tenantId.trim();
        if (JdbcNotificationPort.RESERVED_TENANT.equals(tid)) {
            throw new AccessDeniedException("reserved-tenant");
        }
        tenantAccess.requireGranted(operator, tid);
        policyEngine.require(
                PolicyPrincipal.from(operator),
                OperatorPermission.NOTIFICATION_READ.permissionName(),
                AccessAction.of("read"),
                PolicyResource.of("notification", "_").withAttribute(PolicyResource.ATTR_TENANT_ID, tid),
                PolicyContext.of(tid, false, OrganizationScope.unrestricted()));
        return tid;
    }

    private static OutboxState parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return OutboxState.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown notification status: " + raw.trim());
        }
    }

    private static NotificationChannel parseChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return NotificationChannel.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown notification channel: " + raw.trim());
        }
    }
}
