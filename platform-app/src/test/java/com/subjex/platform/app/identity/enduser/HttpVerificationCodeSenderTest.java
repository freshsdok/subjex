package com.subjex.platform.app.identity.enduser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * HttpVerificationCodeSenderTest — vendor-shaped template posts through a test double only.
 * 厂商形模板只经测试替身投递，不呼叫真实厂商网络；异常不得带出验证码或密钥。
 */
class HttpVerificationCodeSenderTest {

    @Test
    void sendPostsGenericPayloadToTheConfiguredEndpoint() {
        RecordingClient client = new RecordingClient();
        HttpVerificationCodeSender sender = new HttpVerificationCodeSender(
                client, "https://sms.example.invalid/v1/otp", "test-api-key", "Subjex");
        assertTrue(sender.configured());
        sender.send("13800138000", VerificationCodePort.PURPOSE_LOGIN, "123456");
        assertEquals("https://sms.example.invalid/v1/otp", client.endpoint);
        assertEquals("test-api-key", client.apiKey);
        assertEquals(
                "{\"destination\":\"13800138000\",\"purpose\":\"phone-login\",\"code\":\"123456\",\"from\":\"Subjex\"}",
                client.jsonBody);
    }

    @Test
    void transportFailureDoesNotLeakTheCodeOrApiKey() {
        RecordingClient client = new RecordingClient();
        client.failWith = new RuntimeException(
                "upstream said code=123456 apiKey=test-api-key body={\"code\":\"123456\"}");
        HttpVerificationCodeSender sender = new HttpVerificationCodeSender(
                client, "https://sms.example.invalid/v1/otp", "test-api-key", null);
        VerificationCodeSendFailedException ex = assertThrows(
                VerificationCodeSendFailedException.class,
                () -> sender.send("13800138000", VerificationCodePort.PURPOSE_LOGIN, "123456"));
        assertEquals(VerificationCodeSendFailedException.REASON, ex.getMessage());
        assertNull(ex.getCause());
        assertFalse(ex.getMessage().contains("123456"));
        assertFalse(ex.getMessage().contains("test-api-key"));
    }

    @Test
    void configuredSenderFailureKeepsTheStableReason() {
        RecordingClient client = new RecordingClient();
        client.failWith = new VerificationCodeSendFailedException();
        HttpVerificationCodeSender sender = new HttpVerificationCodeSender(
                client, "https://sms.example.invalid/v1/otp", "test-api-key", null);
        VerificationCodeSendFailedException ex = assertThrows(
                VerificationCodeSendFailedException.class,
                () -> sender.send("13800138000", VerificationCodePort.PURPOSE_BIND, "654321"));
        assertEquals(VerificationCodeSendFailedException.REASON, ex.getMessage());
        assertFalse(ex.getMessage().contains("654321"));
    }

    private static final class RecordingClient implements VerificationCodeHttpClient {
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
