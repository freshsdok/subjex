package com.subjex.platform.contract.notification;

import com.subjex.platform.contract.task.OutboxState;

/**
 * NotificationReceipt — 入队回执：稳定标识等于出箱 {@code event_id}，可供随后查看状态。
 * <p>
 * {@code notificationId} is the {@code outbox_event.event_id}. Phase 1 leaves the row {@code PENDING}
 * for a later channel dispatcher (WP5-2); this receipt does not mean a vendor send already happened.
 * {@code notificationId} 即出箱事件标识。本切片将行留在 {@code PENDING}，等待后续渠道调度（WP5-2）；回执不表示厂商已发送。
 */
public record NotificationReceipt(
        String notificationId, String tenantId, String eventName, OutboxState eventState) {}
