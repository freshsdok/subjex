package com.subjex.platform.app.payment;

import com.subjex.platform.app.security.ProductionProfileGuard;
import com.subjex.platform.contract.delivery.SecretPlaceholderGuard;
import com.subjex.platform.contract.payment.PaymentProviderAdapter;
import com.subjex.platform.contract.payment.PaymentProviderIds;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * PaymentProviderAdapterFactory — selects each known payment provider adapter from explicit configuration.
 * 按显式配置为每个已知供应商标识选择适配器。
 * <p>
 * Production defaults to fail-closed. {@code adapter=mock} (or {@code dev}) is refused under
 * prod/production. {@code adapter=http} uses the vendor-shaped HTTP template only when the base
 * URL, API key, and merchant id are complete; missing or placeholder secrets fail closed and never
 * fall back to the mock adapter that would appear to have charged or refunded.
 * The in-memory mock is only used for {@link PaymentProviderIds#MOCK} in non-production.
 * Reserved {@code WECHAT_PAY} / {@code ALIPAY} names never receive the mock, even on a laptop.
 * 生产默认失败关闭。生产禁止 {@code adapter=mock}（或 {@code dev}）。{@code adapter=http} 仅在端点、密钥与
 * 商户号配齐时启用厂商形 HTTP 模板；缺配或占位密钥失败关闭，绝不回退到「看起来已经扣费或退款」的模拟适配器。
 * 内存模拟只用于非生产的 {@link PaymentProviderIds#MOCK}。预留的微信支付/支付宝标识即使在本机也不会挂模拟适配器。
 */
public final class PaymentProviderAdapterFactory {

    /** Fail-closed stand-in — 失败关闭占位。 */
    public static final String ADAPTER_FAIL_CLOSED = "fail-closed";

    /** In-memory mock / dev adapter (non-production {@code MOCK} only) — 内存模拟适配器（仅非生产的 MOCK）。 */
    public static final String ADAPTER_MOCK = "mock";

    /** Vendor-shaped HTTP template — 厂商形 HTTP 模板。 */
    public static final String ADAPTER_HTTP = "http";

    private static final List<String> KNOWN_PROVIDERS =
            List.of(PaymentProviderIds.MOCK, PaymentProviderIds.WECHAT_PAY, PaymentProviderIds.ALIPAY);

    private PaymentProviderAdapterFactory() {}

    /**
     * Build one adapter per known provider id, using empty settings when a provider is omitted.
     * 为每个已知供应商标识构造适配器；未给出的视为空配置。
     */
    public static List<PaymentProviderAdapter> createAll(
            String[] activeProfiles,
            Map<String, PaymentProviderSettings> settings,
            PaymentHttpClient httpClient,
            RecordingPaymentSink mockSink) {
        Map<String, PaymentProviderSettings> byProvider = settings == null ? Map.of() : settings;
        List<PaymentProviderAdapter> adapters = new ArrayList<>();
        for (String providerId : KNOWN_PROVIDERS) {
            PaymentProviderSettings providerSettings =
                    byProvider.getOrDefault(providerId, PaymentProviderSettings.empty());
            adapters.add(create(providerId, activeProfiles, providerSettings, httpClient, mockSink));
        }
        return List.copyOf(adapters);
    }

    /**
     * Build the adapter for one provider id.
     * 为单一供应商标识构造适配器。
     */
    public static PaymentProviderAdapter create(
            String providerId,
            String[] activeProfiles,
            PaymentProviderSettings settings,
            PaymentHttpClient httpClient,
            RecordingPaymentSink mockSink) {
        String id = requireProviderId(providerId);
        PaymentProviderSettings resolved = settings == null ? PaymentProviderSettings.empty() : settings;
        boolean production = ProductionProfileGuard.isProductionProfile(activeProfiles);
        String selected = normalize(resolved.adapter());
        if (production && (ADAPTER_MOCK.equals(selected) || "dev".equals(selected))) {
            throw new IllegalStateException(
                    "platform.payment."
                            + configKey(id)
                            + ".adapter=mock is not allowed under prod/production");
        }
        if (ADAPTER_HTTP.equals(selected)) {
            if (httpConfigured(resolved.httpBaseUrl(), resolved.httpApiKey(), resolved.httpMerchantId())
                    && httpClient != null) {
                return new HttpPaymentAdapter(
                        id,
                        httpClient,
                        resolved.httpBaseUrl().trim(),
                        resolved.httpApiKey().trim(),
                        resolved.httpMerchantId().trim());
            }
            return new FailClosedPaymentAdapter(id);
        }
        if (ADAPTER_FAIL_CLOSED.equals(selected) || (production && selected.isEmpty())) {
            return new FailClosedPaymentAdapter(id);
        }
        if (!production
                && PaymentProviderIds.MOCK.equals(id)
                && (selected.isEmpty() || ADAPTER_MOCK.equals(selected) || "dev".equals(selected))) {
            return new MockPaymentAdapter(mockSink != null ? mockSink : new RecordingPaymentSink());
        }
        return new FailClosedPaymentAdapter(id);
    }

    /**
     * YAML / env key segment for a provider ({@code mock}, {@code wechat-pay}, {@code alipay}).
     * 供应商在配置中的键名。
     */
    public static String configKey(String providerId) {
        return requireProviderId(providerId).toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static boolean httpConfigured(String httpBaseUrl, String httpApiKey, String httpMerchantId) {
        if (isBlank(httpBaseUrl)
                || isBlank(httpApiKey)
                || isBlank(httpMerchantId)
                || SecretPlaceholderGuard.looksLikePlaceholder(httpApiKey)
                || SecretPlaceholderGuard.looksLikePlaceholder(httpMerchantId)) {
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

    /**
     * Index adapters by provider id for tests — 测试用的按供应商标识索引。
     */
    public static Map<String, PaymentProviderAdapter> index(List<PaymentProviderAdapter> adapters) {
        LinkedHashMap<String, PaymentProviderAdapter> map = new LinkedHashMap<>();
        for (PaymentProviderAdapter adapter : adapters) {
            map.putIfAbsent(adapter.providerId(), adapter);
        }
        return Map.copyOf(map);
    }

    private static String requireProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId required");
        }
        return providerId.trim();
    }

    private static String normalize(String adapter) {
        return adapter == null ? "" : adapter.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
