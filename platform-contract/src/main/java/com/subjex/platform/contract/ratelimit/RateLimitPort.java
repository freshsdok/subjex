package com.subjex.platform.contract.ratelimit;

/**
 * RateLimitPort — 限流端口：平台唯一的速率判断入口。
 * <p>
 * One port. Callers ask whether this tenant may perform this action now.
 * 只有这一个端口。调用方询问该租户此刻能否执行这个动作。
 */
public interface RateLimitPort {

    /**
     * @param actionName the platform action being limited, such as {@code submit-task}
     *                   被限制的平台动作，例如 {@code submit-task}
     * @return {@code true} when the call may proceed / 可以继续时为 {@code true}
     */
    boolean permit(String tenantId, String actionName);
}
