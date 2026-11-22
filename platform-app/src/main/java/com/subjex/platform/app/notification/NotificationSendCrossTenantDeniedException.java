package com.subjex.platform.app.notification;

/**
 * NotificationSendCrossTenantDeniedException — tenant A may not read tenant B's notification send record.
 * 跨租户阅读发送记录被拒绝：租户 A 不得读取租户 B 的通知发送记录。
 */
public final class NotificationSendCrossTenantDeniedException extends RuntimeException {

    public static final String REASON = "notification-cross-tenant-denied";

    public NotificationSendCrossTenantDeniedException(String requestTenantId, String eventTenantId) {
        super("cross-tenant notification send query denied: tenant "
                + requestTenantId
                + " cannot read a send record belonging to "
                + eventTenantId);
    }
}
