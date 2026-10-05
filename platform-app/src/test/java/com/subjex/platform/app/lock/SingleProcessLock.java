package com.subjex.platform.app.lock;

import com.subjex.platform.contract.lock.DistributedLockPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SingleProcessLock — 单进程锁：{@link DistributedLockPort} 的测试用实现。
 * <p>
 * Tests only. The map lives in this JVM and does not coordinate another process. platform-app wires {@code JdbcRowLock}.
 * 只用于测试。这张表只活在本 JVM 里，不协调另一个进程。platform-app 装配的是 {@code JdbcRowLock}。
 */
public final class SingleProcessLock implements DistributedLockPort {

    private final Clock clock;
    private final ConcurrentHashMap<String, Hold> holds = new ConcurrentHashMap<>();

    public SingleProcessLock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized boolean tryAcquire(String lockName, String ownerToken, Duration holdFor) {
        requireNames(lockName, ownerToken);
        Instant now = clock.instant();
        Hold current = holds.get(lockName);
        if (current != null && current.until().isAfter(now) && !current.ownerToken().equals(ownerToken)) {
            return false;
        }
        holds.put(lockName, new Hold(ownerToken, now.plus(holdFor)));
        return true;
    }

    @Override
    public synchronized boolean release(String lockName, String ownerToken) {
        requireNames(lockName, ownerToken);
        Hold current = holds.get(lockName);
        if (current == null || !current.ownerToken().equals(ownerToken)) {
            return false;
        }
        holds.remove(lockName, current);
        return true;
    }

    private static void requireNames(String lockName, String ownerToken) {
        if (lockName == null || lockName.isBlank() || ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("lock name and owner are required");
        }
    }

    private record Hold(String ownerToken, Instant until) {
    }
}
