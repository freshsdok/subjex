package com.subjex.platform.app.lock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.lock.DistributedLockPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Contract of the single-process lock — 单进程锁的契约。
 * The implementation does not claim to span processes.
 * 这个实现并不声称自己跨进程。
 */
class SingleProcessLockTest {

    @Test
    void secondOwnerWaitsUntilTheHolderReleasesOrTheHoldExpires() {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-10-05T00:00:00Z"));
        DistributedLockPort lock = new SingleProcessLock(clock);

        assertTrue(lock.tryAcquire("task-submit/tenant-north", "owner-a", Duration.ofSeconds(30)));
        assertFalse(lock.tryAcquire("task-submit/tenant-north", "owner-b", Duration.ofSeconds(30)));
        assertFalse(lock.release("task-submit/tenant-north", "owner-b"));
        assertFalse(lock.tryAcquire("task-submit/tenant-north", "owner-b", Duration.ofSeconds(30)));

        assertTrue(lock.release("task-submit/tenant-north", "owner-a"));
        assertTrue(lock.tryAcquire("task-submit/tenant-north", "owner-b", Duration.ofSeconds(30)));
        assertTrue(lock.release("task-submit/tenant-north", "owner-b"));

        assertTrue(lock.tryAcquire("task-submit/tenant-north", "owner-a", Duration.ofSeconds(10)));
        clock.advance(Duration.ofSeconds(11));
        assertTrue(lock.tryAcquire("task-submit/tenant-north", "owner-b", Duration.ofSeconds(10)));
    }

    private static final class AdjustableClock extends Clock {
        private Instant now;

        private AdjustableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
