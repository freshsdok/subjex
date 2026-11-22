package com.subjex.platform.app.payment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.payment.PaymentProviderAdapter;
import com.subjex.platform.contract.payment.PaymentProviderIds;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PaymentProviderAdapterFactoryTest — explicit adapter selection stays fail-closed by default.
 * 支付适配器须显式配置，默认失败关闭；缺配不得回退到模拟适配器。
 */
class PaymentProviderAdapterFactoryTest {

    private static final String[] PROD = {"prod"};
    private static final String[] LOCAL = {"local"};
    private static final String[] NONE = {};
    private static final RecordingClient HTTP = new RecordingClient();
    private static final RecordingPaymentSink SINK = new RecordingPaymentSink();

    @Test
    void productionDefaultIsFailClosedForEveryKnownProvider() {
        Map<String, PaymentProviderAdapter> adapters =
                PaymentProviderAdapterFactory.index(
                        PaymentProviderAdapterFactory.createAll(PROD, Map.of(), HTTP, SINK));
        assertInstanceOf(FailClosedPaymentAdapter.class, adapters.get(PaymentProviderIds.MOCK));
        assertInstanceOf(FailClosedPaymentAdapter.class, adapters.get(PaymentProviderIds.WECHAT_PAY));
        assertInstanceOf(FailClosedPaymentAdapter.class, adapters.get(PaymentProviderIds.ALIPAY));
        assertFalse(adapters.get(PaymentProviderIds.MOCK).configured());
        assertFalse(adapters.get(PaymentProviderIds.WECHAT_PAY).configured());
    }

    @Test
    void productionRefusesMockAdapter() {
        assertThrows(
                IllegalStateException.class,
                () -> PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.MOCK,
                        PROD,
                        new PaymentProviderSettings("mock", "", "", ""),
                        HTTP,
                        SINK));
        assertThrows(
                IllegalStateException.class,
                () -> PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.WECHAT_PAY,
                        new String[] {"production"},
                        new PaymentProviderSettings("dev", "", "", ""),
                        HTTP,
                        SINK));
    }

    @Test
    void productionHttpWithoutCompleteConfigStaysFailClosed() {
        assertInstanceOf(
                FailClosedPaymentAdapter.class,
                PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.WECHAT_PAY,
                        PROD,
                        new PaymentProviderSettings("http", "", "", ""),
                        HTTP,
                        SINK));
        assertInstanceOf(
                FailClosedPaymentAdapter.class,
                PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.WECHAT_PAY,
                        PROD,
                        new PaymentProviderSettings(
                                "http", "https://pay.example.invalid/v1/charges", "test-api-key", ""),
                        HTTP,
                        SINK));
        assertInstanceOf(
                FailClosedPaymentAdapter.class,
                PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.ALIPAY,
                        PROD,
                        new PaymentProviderSettings(
                                "http", "https://pay.example.invalid/v1/charges", "change-me", "mch-1"),
                        HTTP,
                        SINK));
        assertInstanceOf(
                FailClosedPaymentAdapter.class,
                PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.WECHAT_PAY,
                        PROD,
                        new PaymentProviderSettings(
                                "http", "https://pay.example.invalid/v1/charges", "test-api-key", "mch-1"),
                        null,
                        SINK));
    }

    @Test
    void productionHttpWithCompleteConfigSelectsTheTemplate() {
        PaymentProviderAdapter adapter = PaymentProviderAdapterFactory.create(
                PaymentProviderIds.ALIPAY,
                PROD,
                new PaymentProviderSettings(
                        "http", "https://pay.example.invalid/v1/charges", "test-api-key", "mch-1"),
                HTTP,
                SINK);
        assertInstanceOf(HttpPaymentAdapter.class, adapter);
        assertTrue(adapter.configured());
        org.junit.jupiter.api.Assertions.assertEquals(PaymentProviderIds.ALIPAY, adapter.providerId());
    }

    @Test
    void nonProductionEmptyOrMockUsesMockOnlyForMockProvider() {
        assertInstanceOf(
                MockPaymentAdapter.class,
                PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.MOCK, NONE, PaymentProviderSettings.empty(), HTTP, SINK));
        assertInstanceOf(
                MockPaymentAdapter.class,
                PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.MOCK,
                        LOCAL,
                        new PaymentProviderSettings("mock", "", "", ""),
                        HTTP,
                        SINK));
        // Reserved names never get the mock, even on a laptop.
        // 预留标识即使在本机也不挂模拟适配器。
        assertInstanceOf(
                FailClosedPaymentAdapter.class,
                PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.WECHAT_PAY, LOCAL, PaymentProviderSettings.empty(), HTTP, SINK));
        assertInstanceOf(
                FailClosedPaymentAdapter.class,
                PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.ALIPAY,
                        LOCAL,
                        new PaymentProviderSettings("mock", "", "", ""),
                        HTTP,
                        SINK));
    }

    @Test
    void explicitFailClosedWinsInNonProduction() {
        assertInstanceOf(
                FailClosedPaymentAdapter.class,
                PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.MOCK,
                        LOCAL,
                        new PaymentProviderSettings("fail-closed", "", "", ""),
                        HTTP,
                        SINK));
    }

    @Test
    void explicitHttpDoesNotFallBackToMockWhenIncomplete() {
        PaymentProviderAdapter adapter = PaymentProviderAdapterFactory.create(
                PaymentProviderIds.WECHAT_PAY,
                LOCAL,
                new PaymentProviderSettings("http", "https://pay.example.invalid/v1/charges", "", "mch-1"),
                HTTP,
                SINK);
        assertInstanceOf(FailClosedPaymentAdapter.class, adapter);
        assertFalse(adapter.configured());
    }

    @Test
    void unknownAdapterIsFailClosed() {
        assertInstanceOf(
                FailClosedPaymentAdapter.class,
                PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.MOCK,
                        PROD,
                        new PaymentProviderSettings("unknown", "", "", ""),
                        HTTP,
                        SINK));
        assertInstanceOf(
                FailClosedPaymentAdapter.class,
                PaymentProviderAdapterFactory.create(
                        PaymentProviderIds.MOCK,
                        LOCAL,
                        new PaymentProviderSettings("unknown", "", "", ""),
                        HTTP,
                        SINK));
    }

    private static final class RecordingClient implements PaymentHttpClient {
        @Override
        public void post(String endpoint, String apiKey, String jsonBody) {
            throw new IllegalStateException("factory tests must not send");
        }
    }
}
