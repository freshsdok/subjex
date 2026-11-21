package com.subjex.platform.contract.task;

/**
 * TaskRecordedNotice — 任务已记录通知：跨进程发出的那一条事件。
 * <p>
 * Historic cross-process task event. WP5 adds {@code NotificationRequested} on the same
 * {@code outbox_event} table; that name is not this notice and is not relayed here in WP5-1.
 * 既有的跨进程任务事件。WP5 在同一张 {@code outbox_event} 上增加 {@code NotificationRequested}；
 * 那不是本通知，WP5-1 也不经本中继投递。
 */
public record TaskRecordedNotice(String eventId, String tenantId, String taskId, String stepName) {

    /** Wire name of the only cross-process event — 唯一跨进程事件在线路上的名字。 */
    public static final String EVENT_NAME = "TaskRecorded";
}
