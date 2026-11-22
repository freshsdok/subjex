package com.subjex.platform.app.notification;

import com.subjex.platform.app.security.ProductionProfileGuard;
import com.subjex.platform.contract.delivery.SecretPlaceholderGuard;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationChannelAdapter;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * NotificationChannelAdapterFactory — selects each channel adapter from explicit configuration.
 * 按显式配置为每个渠道选择适配器。
 * <p>
 * Production defaults to fail-closed. {@code adapter=mock} (or {@code dev}) is refused under
 * prod/production. {@code adapter=http} uses the vendor-shaped HTTP template only when the base
 * URL and API key are complete; missing or placeholder secrets fail closed and never fall back
 * to the mock adapter that would appear to have sent.
 * 生产默认失败关闭。生产禁止 {@code adapter=mock}（或 {@code dev}）。{@code adapter=http} 仅在端点与
 * 密钥配齐时启用厂商形 HTTP 模板；缺配或占位密钥失败关闭，绝不回退到「看起来已经发出」的模拟适配器。
 */
public final class NotificationChannelAdapterFactory {

    /** Fail-closed stand-in — 失败关闭占位。 */
    public static final String ADAPTER_FAIL_CLOSED = "fail-closed";

    /** In-memory mock / dev adapter (non-production only) — 内存模拟适配器（仅非生产）。 */
    public static final String ADAPTER_MOCK = "mock";

    /** Vendor-shaped HTTP template — 厂商形 HTTP 模板。 */
    public static final String ADAPTER_HTTP = "http";

    private NotificationChannelAdapterFactory() {}

    /**
     * Build one adapter per {@link NotificationChannel}, using empty settings when a channel is omitted.
     * 为每个渠道构造适配器；未给出的渠道视为空配置。
     */
    public static List<NotificationChannelAdapter> createAll(
            String[] activeProfiles,
            Map<NotificationChannel, NotificationChannelSettings> settings,
            NotificationHttpClient httpClient,
            RecordingNotificationSink mockSink) {
        Map<NotificationChannel, NotificationChannelSettings> byChannel =
                settings == null ? Map.of() : settings;
        List<NotificationChannelAdapter> adapters = new ArrayList<>();
        for (NotificationChannel channel : NotificationChannel.values()) {
            NotificationChannelSettings channelSettings =
                    byChannel.getOrDefault(channel, NotificationChannelSettings.empty());
            adapters.add(create(channel, activeProfiles, channelSettings, httpClient, mockSink));
        }
        return List.copyOf(adapters);
    }

    /**
     * Build the adapter for one channel.
     * 为单一渠道构造适配器。
     */
    public static NotificationChannelAdapter create(
            NotificationChannel channel,
            String[] activeProfiles,
            NotificationChannelSettings settings,
            NotificationHttpClient httpClient,
            RecordingNotificationSink mockSink) {
        Objects.requireNonNull(channel, "channel");
        NotificationChannelSettings resolved =
                settings == null ? NotificationChannelSettings.empty() : settings;
        boolean production = ProductionProfileGuard.isProductionProfile(activeProfiles);
        String selected = normalize(resolved.adapter());
        if (production && (ADAPTER_MOCK.equals(selected) || "dev".equals(selected))) {
            throw new IllegalStateException(
                    "platform.notification."
                            + configKey(channel)
                            + ".adapter=mock is not allowed under prod/production");
        }
        if (ADAPTER_HTTP.equals(selected)) {
            if (httpConfigured(resolved.httpBaseUrl(), resolved.httpApiKey()) && httpClient != null) {
                return new HttpNotificationChannelAdapter(
                        channel,
                        httpClient,
                        resolved.httpBaseUrl().trim(),
                        resolved.httpApiKey().trim(),
                        blankToNull(resolved.httpFrom()));
            }
            return new FailClosedNotificationAdapter(channel);
        }
        if (ADAPTER_FAIL_CLOSED.equals(selected) || (production && selected.isEmpty())) {
            return new FailClosedNotificationAdapter(channel);
        }
        if (!production && (selected.isEmpty() || ADAPTER_MOCK.equals(selected) || "dev".equals(selected))) {
            return new MockNotificationAdapter(
                    channel, mockSink != null ? mockSink : new RecordingNotificationSink());
        }
        return new FailClosedNotificationAdapter(channel);
    }

    /**
     * YAML / env key segment for a channel ({@code sms}, {@code email}, {@code wechat-template}).
     * 渠道在配置中的键名。
     */
    public static String configKey(NotificationChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return channel.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static boolean httpConfigured(String httpBaseUrl, String httpApiKey) {
        if (isBlank(httpBaseUrl)
                || isBlank(httpApiKey)
                || SecretPlaceholderGuard.looksLikePlaceholder(httpApiKey)) {
            return false;
        }
        try {
            URI uri = URI.create(httpBaseUrl.trim());
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String normalize(String adapter) {
        return adapter == null ? "" : adapter.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * Index adapters by channel for tests — 测试用的按渠道索引。
     */
    public static Map<NotificationChannel, NotificationChannelAdapter> index(
            List<NotificationChannelAdapter> adapters) {
        EnumMap<NotificationChannel, NotificationChannelAdapter> map =
                new EnumMap<>(NotificationChannel.class);
        for (NotificationChannelAdapter adapter : adapters) {
            map.put(adapter.channel(), adapter);
        }
        return Map.copyOf(map);
    }
}
