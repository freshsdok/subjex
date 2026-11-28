package com.subjex.platform.contract.schedule;

/**
 * ScheduleNotFoundException — The schedule does not exist in this tenant. / 该调度在本租户不存在。
 */
public final class ScheduleNotFoundException extends RuntimeException {

    public static final String REASON = "schedule-not-found";

    public ScheduleNotFoundException(String message) {
        super(message);
    }
}
