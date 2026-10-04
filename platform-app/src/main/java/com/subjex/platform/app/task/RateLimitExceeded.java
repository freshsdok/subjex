package com.subjex.platform.app.task;

/**
 * RateLimitExceeded — 超过限流：该租户在当前时间窗里不能再执行这个动作。
 */
public final class RateLimitExceeded extends RuntimeException {

    public RateLimitExceeded() {
        super("rate limit exceeded");
    }
}
