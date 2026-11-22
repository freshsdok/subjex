package com.subjex.platform.app.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * RateLimitWindowView — thin read of current fixed-window counters on the same rate-limit bean.
 * 在同一限流实现上只读当前固定时间窗计数（不是另做一套指标产品）。
 */
public interface RateLimitWindowView {

    int permits();

    Duration window();

    /**
     * Windows that are still counting at {@code now}. Expired rows are omitted, not invented.
     * 此刻仍在计数的时间窗。已过期的不列出，也不编造。
     */
    List<WindowRow> activeWindows(Instant now);

    /**
     * One tenant/action window. {@code remaining} is {@code permits - used}, not a rejection count.
     * 一个租户/动作时间窗。{@code remaining} 是剩余许可次数，不是拒绝次数。
     */
    record WindowRow(
            String bucketKey,
            String tenantId,
            String actionName,
            Instant windowStartedAt,
            Instant windowEndsAt,
            int used,
            int remaining) {

        static WindowRow fromBucket(String bucketKey, Instant startedAt, int used, int permits, Duration window) {
            int slash = bucketKey.indexOf('/');
            String tenant = slash < 0 ? "" : bucketKey.substring(0, slash);
            String action = slash < 0 ? bucketKey : bucketKey.substring(slash + 1);
            int remaining = Math.max(0, permits - used);
            return new WindowRow(bucketKey, tenant, action, startedAt, startedAt.plus(window), used, remaining);
        }
    }
}
