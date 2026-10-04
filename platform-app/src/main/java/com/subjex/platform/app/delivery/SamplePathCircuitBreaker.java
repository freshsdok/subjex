package com.subjex.platform.app.delivery;

/**
 * SamplePathCircuitBreaker — 示例路径熔断器：连续失败达到阈值后，不再调用 sample-consumer。
 * <p>
 * The breaker guards only the sample delivery path. A success clears the streak. Once open, it stays open for this process.
 * 熔断器只看守示例投递路径。一次成功会清掉连续失败。打开之后，在本进程内保持打开。
 */
public final class SamplePathCircuitBreaker {

    private final int failureThreshold;
    private int consecutiveFailures;
    private boolean open;

    public SamplePathCircuitBreaker(int failureThreshold) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failure threshold must be at least 1");
        }
        this.failureThreshold = failureThreshold;
    }

    public synchronized boolean allowCall() {
        return !open;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        open = false;
    }

    public synchronized void recordFailure() {
        if (open) {
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            open = true;
        }
    }

    public synchronized boolean isOpen() {
        return open;
    }
}
