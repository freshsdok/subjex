package com.subjex.platform.app.identity.enduser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * VerificationCodeSenderFactoryTest — explicit sender selection stays fail-closed by default.
 * 发送器选择须显式配置，默认失败关闭；缺配不得回退到开发发送器。
 */
class VerificationCodeSenderFactoryTest {

    private static final String[] PROD = {"prod"};
    private static final String[] LOCAL = {"local"};
    private static final String[] NONE = {};
    private static final RecordingHttpClient HTTP = new RecordingHttpClient();

    @Test
    void productionDefaultIsFailClosed() {
        VerificationCodeSender sender = VerificationCodeSenderFactory.create(
                PROD, false, "", "", "", "", HTTP);
        assertInstanceOf(FailClosedVerificationCodeSender.class, sender);
        assertFalse(sender.configured());
    }

    @Test
    void productionRefusesDevSenderAndReveal() {
        assertThrows(
                IllegalStateException.class,
                () -> VerificationCodeSenderFactory.create(
                        PROD, false, "dev", "", "", "", HTTP));
        assertThrows(
                IllegalStateException.class,
                () -> VerificationCodeSenderFactory.create(
                        new String[] {"production"}, true, "", "", "", "", HTTP));
    }

    @Test
    void productionHttpWithoutCompleteConfigStaysFailClosed() {
        assertInstanceOf(
                FailClosedVerificationCodeSender.class,
                VerificationCodeSenderFactory.create(
                        PROD, false, "http", "", "", "", HTTP));
        assertInstanceOf(
                FailClosedVerificationCodeSender.class,
                VerificationCodeSenderFactory.create(
                        PROD,
                        false,
                        "http",
                        "https://sms.example.invalid/v1/otp",
                        "",
                        "",
                        HTTP));
        assertInstanceOf(
                FailClosedVerificationCodeSender.class,
                VerificationCodeSenderFactory.create(
                        PROD,
                        false,
                        "http",
                        "https://sms.example.invalid/v1/otp",
                        "change-me",
                        "",
                        HTTP));
        assertInstanceOf(
                FailClosedVerificationCodeSender.class,
                VerificationCodeSenderFactory.create(
                        PROD,
                        false,
                        "http",
                        "https://sms.example.invalid/v1/otp",
                        "test-api-key",
                        "",
                        null));
    }

    @Test
    void productionHttpWithCompleteConfigSelectsTheTemplate() {
        VerificationCodeSender sender = VerificationCodeSenderFactory.create(
                PROD,
                false,
                "http",
                "https://sms.example.invalid/v1/otp",
                "test-api-key",
                "Subjex",
                HTTP);
        assertInstanceOf(HttpVerificationCodeSender.class, sender);
        assertTrue(sender.configured());
    }

    @Test
    void nonProductionEmptyOrDevUsesDevSender() {
        assertInstanceOf(
                DevVerificationCodeSender.class,
                VerificationCodeSenderFactory.create(NONE, false, "", "", "", "", HTTP));
        assertInstanceOf(
                DevVerificationCodeSender.class,
                VerificationCodeSenderFactory.create(LOCAL, true, "dev", "", "", "", HTTP));
    }

    @Test
    void explicitFailClosedWinsInNonProduction() {
        assertInstanceOf(
                FailClosedVerificationCodeSender.class,
                VerificationCodeSenderFactory.create(
                        LOCAL, true, "fail-closed", "", "", "", HTTP));
    }

    @Test
    void explicitHttpDoesNotFallBackToDevWhenIncomplete() {
        VerificationCodeSender sender = VerificationCodeSenderFactory.create(
                LOCAL, true, "http", "https://sms.example.invalid/v1/otp", "", "", HTTP);
        assertInstanceOf(FailClosedVerificationCodeSender.class, sender);
        assertFalse(sender.configured());
    }

    @Test
    void unknownSenderIsFailClosed() {
        assertInstanceOf(
                FailClosedVerificationCodeSender.class,
                VerificationCodeSenderFactory.create(PROD, false, "unknown", "", "", "", HTTP));
        assertInstanceOf(
                FailClosedVerificationCodeSender.class,
                VerificationCodeSenderFactory.create(LOCAL, true, "unknown", "", "", "", HTTP));
    }

    /** Recording transport used only as a non-null hook — 仅作非空传输钩子，不发起网络。 */
    private static final class RecordingHttpClient implements VerificationCodeHttpClient {
        @Override
        public void post(String endpoint, String apiKey, String jsonBody) {
            throw new IllegalStateException("factory tests must not send");
        }
    }
}
