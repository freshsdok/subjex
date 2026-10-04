package com.subjex.platform.contract.task;

/**
 * TaskRecordedNotice — 任务已记录通知：跨进程发出的那一条事件。
 * <p>
 * v1 has this single event name. The consumer subscribes to it and to nothing else.
 * 第一版只有这一个事件名。消费者只订阅它。
 */
public record TaskRecordedNotice(String eventId, String tenantId, String taskId, String stepName) {

    /** Wire name of the only cross-process event — 唯一跨进程事件在线路上的名字。 */
    public static final String EVENT_NAME = "TaskRecorded";
}
