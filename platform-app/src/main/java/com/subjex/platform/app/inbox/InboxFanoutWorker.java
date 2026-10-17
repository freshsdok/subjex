package com.subjex.platform.app.inbox;

import com.subjex.platform.contract.inbox.InboxPort;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * InboxFanoutWorker — thin after-commit trigger that drains {@code inbox_fanout_*} into {@code inbox_entry}.
 * <p>
 * Does not use Delivery/Outbox as inbox. Schedules on the injected {@link Executor} after commit when a TX
 * is active; otherwise executes immediately (tests often use {@code Runnable::run}).
 * 薄触发器：提交后把暂存收件人写成 inbox_entry；Outbox 不当收件箱。
 */
public final class InboxFanoutWorker {

    /** Default JDBC batch size per processFanoutBatch call — 默认每批写入条数. */
    public static final int DEFAULT_BATCH_SIZE = 100;

    private final InboxPort inbox;
    private final Executor executor;

    public InboxFanoutWorker(InboxPort inbox, Executor executor) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Schedule drain of one job after the current transaction commits (or immediately if none).
     * 在当前事务提交后调度排空；无事务则立即执行。
     */
    public void schedule(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        String id = jobId.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        Runnable task = () -> drain(id);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
        } else {
            executor.execute(task);
        }
    }

    /** Drain until processFanoutBatch returns 0 — 排空直至批次返回 0. */
    public void drain(String jobId) {
        while (inbox.processFanoutBatch(jobId, DEFAULT_BATCH_SIZE) > 0) {
            // continue until staged recipients are gone
        }
    }
}
