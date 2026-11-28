package com.subjex.platform.app.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import org.springframework.scheduling.support.CronExpression;

/**
 * ScheduleCron — parse and evaluate 6-field Spring cron expressions in a fixed zone.
 * 解析并在固定时区计算 6 段 Spring cron 表达式。
 * <p>
 * Evaluation zone is always {@code Asia/Shanghai} (UTC+8), the product/operator zone. The platform
 * {@link java.time.Clock} bean remains UTC; instants are stored in UTC. There is no per-user or
 * per-tenant timezone.
 * 计算时区固定为 Asia/Shanghai（UTC+8），即产品/操作员时区。平台 Clock 仍是 UTC；瞬时按 UTC 存储。
 * 没有按用户或按租户的时区。
 */
public final class ScheduleCron {

    /** Fixed cron evaluation zone — cron 计算的固定时区. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** Documented timezone name stored on API records — API 记录上写明的时区名. */
    public static final String ZONE_NAME = ZONE.getId();

    private ScheduleCron() {}

    /**
     * Parse a 6-field Spring cron (second minute hour day-of-month month day-of-week), or fail closed.
     * 解析 6 段 Spring cron（秒 分 时 日 月 周），无效则失败关闭。
     */
    public static CronExpression requireValid(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("cron expression is required");
        }
        try {
            return CronExpression.parse(expression.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid cron expression: " + expression.trim());
        }
    }

    /**
     * Next fire instant strictly after {@code after}, in UTC. Fail closed when the expression never fires.
     * {@code after} 之后的下一次触发瞬时（UTC）。表达式再无触发则失败关闭。
     */
    public static Instant nextRunAfter(String expression, Instant after) {
        Objects.requireNonNull(after, "after");
        CronExpression cron = requireValid(expression);
        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(after, ZONE));
        if (next == null) {
            throw new IllegalArgumentException("cron expression has no next run: " + expression.trim());
        }
        return next.toInstant();
    }

    /** Idempotency token for one schedule due tick — 一条调度某一到期拍的幂等令牌. */
    public static String taskIdempotencyToken(String scheduleId, Instant dueAt) {
        return "sched:" + requireId(scheduleId, "scheduleId") + ":" + dueAt.toEpochMilli();
    }

    /**
     * Per-tick workflow record id: configured id plus the due epoch, so recurring fires start new instances
     * and a retried tick is unique-key idempotent.
     * 每一拍的工作流记录标识：配置的标识加上到期纪元毫秒，使周期触发能开新实例，重试同一拍则靠唯一键幂等。
     */
    public static String workflowRecordId(String configuredRecordId, Instant dueAt) {
        return requireId(configuredRecordId, "workflowRecordId") + "@" + dueAt.toEpochMilli();
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
