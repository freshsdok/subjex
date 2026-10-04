package com.subjex.platform.contract.lock;

import java.time.Duration;

/**
 * DistributedLockPort — 分布式锁端口：按锁名互斥的唯一入口。
 * <p>
 * A single-process implementation may back this port in v1. It must not pretend to coordinate other processes.
 * 第一版可以用单进程实现托底，但不得伪装成已经协调了其他进程。
 */
public interface DistributedLockPort {

    /**
     * @param ownerToken caller that would hold the lock; only this owner may release it
     *                   想持有锁的一方；只有该持有者可以释放
     * @return {@code true} when this owner now holds the lock / 该持有者现在拿到锁时为 {@code true}
     */
    boolean tryAcquire(String lockName, String ownerToken, Duration holdFor);

    /**
     * @return {@code true} when this owner held the lock and released it
     *         该持有者本来持有锁并且已释放时为 {@code true}
     */
    boolean release(String lockName, String ownerToken);
}
