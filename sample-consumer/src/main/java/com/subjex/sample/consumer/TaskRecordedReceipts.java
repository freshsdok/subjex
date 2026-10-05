package com.subjex.sample.consumer;

import com.subjex.platform.contract.task.TaskRecordedNotice;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TaskRecordedReceipts — 已接收的任务记录：本进程收下的那一条跨进程事件。
 * <p>
 * The count is how many times the outbox socket reached this application, including requested failures.
 * 计数是出箱套接字到达本应用的次数，包含被要求失败的那些。
 */
public final class TaskRecordedReceipts {

    private final AtomicInteger count = new AtomicInteger();
    private final AtomicReference<String> lastTraceId = new AtomicReference<>();
    private final AtomicReference<TaskRecordedNotice> lastNotice = new AtomicReference<>();

    public void accept(TaskRecordedNotice notice, String traceId) {
        lastNotice.set(notice);
        lastTraceId.set(traceId);
        count.incrementAndGet();
    }

    public int count() {
        return count.get();
    }

    public String lastTraceId() {
        return lastTraceId.get();
    }

    public TaskRecordedNotice lastNotice() {
        return lastNotice.get();
    }
}
