package com.subjex.platform.app.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.payment.PaymentAmount;
import com.subjex.platform.contract.payment.PaymentChargePayload;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentProviderIds;
import com.subjex.platform.contract.payment.PaymentResultCallback;
import com.subjex.platform.contract.payment.PaymentStatus;
import com.subjex.platform.contract.payment.RefundCommand;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * HttpPaymentAdapterTest — vendor-shaped template posts through a test double only.
 * 厂商形模板只经测试替身投递，不呼叫真实支付网络；异常不得带出请求体、商户号或密钥。
 */
class HttpPaymentAdapterTest {

    @Test
    void chargeAndRefundPostGenericPayloadToTheConfiguredEndpoint() {
        RecordingClient client = new RecordingClient();
        HttpPaymentAdapter adapter = new HttpPaymentAdapter(
                PaymentProviderIds.WECHAT_PAY,
                client,
                "https://pay.example.invalid/v1/charges",
                "test-api-key",
                "mch-1");
        assertTrue(adapter.configured());
        assertEquals(PaymentProviderIds.WECHAT_PAY, adapter.providerId());
        PaymentDocument current = document();
        adapter.acceptPaymentResult(
                current,
                new PaymentResultCallback("tenant-alpha", current.paymentId(), "notify-1", PaymentStatus.SUCCEEDED, "pref-9"));
        assertEquals("https://pay.example.invalid/v1/charges", client.endpoint);
        assertEquals("test-api-key", client.apiKey);
        assertTrue(client.jsonBody.contains("\"action\":\"payment-result\""));
        assertTrue(client.jsonBody.contains("\"providerId\":\"WECHAT_PAY\""));
        assertTrue(client.jsonBody.contains("\"merchantId\":\"mch-1\""));
        assertTrue(client.jsonBody.contains("\"paymentId\":\"pay-12345678\""));
        adapter.acceptRefund(current, new RefundCommand("tenant-alpha", current.paymentId(), new PaymentAmount(10L, "CNY"), "tok-1"));
        assertTrue(client.jsonBody.contains("\"action\":\"refund\""));
        assertTrue(client.jsonBody.contains("\"refundAmountMinor\":10"));
    }

    @Test
    void startChargePostsGenericHookAndDoesNotMarkSucceeded() {
        RecordingClient client = new RecordingClient();
        HttpPaymentAdapter adapter = new HttpPaymentAdapter(
                PaymentProviderIds.WECHAT_PAY,
                client,
                "https://pay.example.invalid/v1/charges",
                "test-api-key",
                "mch-1");
        PaymentDocument current = document();
        PaymentChargePayload payload = adapter.startCharge(current);
        assertTrue(client.jsonBody.contains("\"action\":\"start-charge\""));
        assertTrue(client.jsonBody.contains("\"paymentId\":\"pay-12345678\""));
        assertEquals(PaymentProviderIds.WECHAT_PAY, payload.providerId());
        assertEquals("HTTP_TEMPLATE", payload.params().get("mode"));
        assertEquals(Boolean.TRUE, payload.params().get("posted"));
        assertEquals(PaymentStatus.PENDING, current.status());
    }

    @Test
    void transportFailureDoesNotLeakTheBodyOrApiKey() {
        RecordingClient client = new RecordingClient();
        client.failWith = new RuntimeException(
                "upstream said paymentId=pay-12345678 apiKey=test-api-key merchantId=mch-1");
        HttpPaymentAdapter adapter = new HttpPaymentAdapter(
                PaymentProviderIds.ALIPAY,
                client,
                "https://pay.example.invalid/v1/charges",
                "test-api-key",
                "mch-1");
        PaymentAdapterSendFailedException ex = assertThrows(
                PaymentAdapterSendFailedException.class,
                () -> adapter.acceptPaymentResult(
                        document(),
                        new PaymentResultCallback(
                                "tenant-alpha", "pay-12345678", "notify-1", PaymentStatus.SUCCEEDED, "pref-9")));
        assertEquals(PaymentAdapterSendFailedException.REASON, ex.getMessage());
        assertNull(ex.getCause());
        assertFalse(ex.getMessage().contains("pay-12345678"));
        assertFalse(ex.getMessage().contains("test-api-key"));
        assertFalse(ex.getMessage().contains("mch-1"));
    }

    @Test
    void maskIdentifierHidesPaymentIdsInAuditTargets() {
        assertEquals("********5678", JdbcPaymentPort.maskIdentifier("pay-12345678"));
        assertEquals(
                "WECHAT_PAY:charge:********5678",
                JdbcPaymentPort.actionTarget(
                        PaymentProviderIds.WECHAT_PAY, JdbcPaymentPort.CHARGE_ACTION, "pay-12345678"));
        assertEquals(
                "ALIPAY:refund:********5678",
                JdbcPaymentPort.actionTarget(PaymentProviderIds.ALIPAY, JdbcPaymentPort.REFUND_ACTION, "pay-12345678"));
        assertFalse(
                JdbcPaymentPort.actionTarget(
                                PaymentProviderIds.WECHAT_PAY, JdbcPaymentPort.CHARGE_ACTION, "pay-12345678")
                        .contains("pay-12345678"));
    }

    private static PaymentDocument document() {
        Instant at = Instant.parse("2026-11-22T00:00:00Z");
        return new PaymentDocument(
                "pay-12345678",
                "tenant-alpha",
                new PaymentAmount(40L, "CNY"),
                new PaymentAmount(0L, "CNY"),
                PaymentStatus.PENDING,
                "ORD-40",
                "payer-ada",
                "END_USER",
                PaymentProviderIds.WECHAT_PAY,
                null,
                List.of(),
                at,
                at,
                at.plus(PaymentLifecycleRules.PENDING_TTL),
                null,
                null);
    }

    private static final class RecordingClient implements PaymentHttpClient {
        String endpoint;
        String apiKey;
        String jsonBody;
        RuntimeException failWith;

        @Override
        public void post(String endpoint, String apiKey, String jsonBody) {
            this.endpoint = endpoint;
            this.apiKey = apiKey;
            this.jsonBody = jsonBody;
            if (failWith != null) {
                throw failWith;
            }
        }
    }
}
