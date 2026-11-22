package com.subjex.platform.app.notification;

/**
 * NotificationSendNotFoundException — no {@code NotificationRequested} send record for this id in the query tenant.
 * 在查询租户下找不到该标识对应的 {@code NotificationRequested} 发送记录。
 */
public final class NotificationSendNotFoundException extends RuntimeException {

    public static final String REASON = "notification-send-not-found";

    public NotificationSendNotFoundException(String tenantId, String eventId) {
        super("notification send not found: tenant " + tenantId + " event " + eventId);
    }
}
