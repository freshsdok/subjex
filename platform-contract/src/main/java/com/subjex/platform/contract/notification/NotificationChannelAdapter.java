package com.subjex.platform.contract.notification;

/**
 * NotificationChannelAdapter — 单一渠道的发送 SPI：内核不写死商业厂商，后续适配器可插入。
 * <p>
 * Phase-1 ships mock/dev adapters. Production must fail closed when no real sender is configured
 * ({@link #configured()} is {@code false}). Implementations send the template id and parameter map,
 * not hard-coded domain prose, and must not log secrets.
 * 首期提供模拟/开发适配器。生产未配置真实发送器时必须失败关闭。实现只发送模板标识与参数，不得写死域文案，也不得在日志中打印密钥或敏感目的地。
 */
public interface NotificationChannelAdapter {

    /** Channel this adapter serves — 本适配器负责的渠道. */
    NotificationChannel channel();

    /**
     * Whether this adapter may actually deliver (production requires a real vendor bean).
     * 是否真正可投递（生产必须换成真实厂商实现）。
     */
    boolean configured();

    /**
     * Deliver one already-persisted {@link NotificationRequested} intent.
     * Must not be treated as a no-op when {@link #configured()} is {@code false}; throw instead.
     * 投递一条已落库的通知意图。未配置时不得当成成功的空操作，应当抛错。
     */
    void send(NotificationRequested request);
}
