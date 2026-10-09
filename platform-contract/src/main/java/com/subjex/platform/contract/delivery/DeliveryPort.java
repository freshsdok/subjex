package com.subjex.platform.contract.delivery;

import com.subjex.platform.contract.task.OutboxEvent;

/**
 * DeliveryPort — 出箱投递端口：把已落库的 PENDING 行送到所选传输。
 * <p>
 * One process selects one transport ({@code platform.delivery.transport}). Implementations must not
 * mark the outbox row; callers ({@code JdbcTaskMessagePort}) record PUBLISHED / PENDING / dead-letter.
 * Failure returns a PENDING {@link com.subjex.platform.contract.task.DeliveryResult} so existing retry
 * keeps the row. {@code failureRequested} is a demo/test hook for the socket path; other transports ignore it.
 * 一个进程只选一种传输。实现不要改出箱行状态；由调用方记账。失败返回 PENDING，交给现有重投。
 * {@code failureRequested} 仅套接字演示/测试用。熔断打开时实现应拒绝调用（失败关闭，不投递）。
 */
public interface DeliveryPort {

    /**
     * Push one stored outbox row — 推送一条已落库的出箱行。
     *
     * @param failureRequested when true, the socket demo consumer may reject after taking the notice
     *                         为 true 时，套接字演示消费者可收下后拒绝；其它传输忽略
     */
    DeliveryAttempt deliver(OutboxEvent event, boolean failureRequested);
}
