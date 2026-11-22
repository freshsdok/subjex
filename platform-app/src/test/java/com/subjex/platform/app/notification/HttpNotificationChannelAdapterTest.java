package com.subjex.platform.app.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationRecipient;
import com.subjex.platform.contract.notification.NotificationRequested;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * HttpNotificationChannelAdapterTest — vendor-shaped template posts through a test double only.
 * 厂商形模板只经测试替身投递，不呼叫真实厂商网络；异常不得带出请求体或密钥。
 */
class HttpNotificationChannelAdapterTest {

    @Test
    void sendPostsGenericPayloadToTheConfiguredEndpoint() {
        RecordingClient client = new RecordingClient();
        HttpNotificationChannelAdapter adapter = new HttpNotificationChannelAdapter(
                NotificationChannel.SMS,
                client,
                "https://notify.example.invalid/v1/sms",
                "test-api-key",
                "Subjex");
        assertTrue(adapter.configured());
        adapter.send(request("13800138000", "123456"));
        assertEquals("https://notify.example.invalid/v1/sms", client.endpoint);
        assertEquals("test-api-key", client.apiKey);
        assertTrue(client.jsonBody.contains("\"channel\":\"SMS\""));
        assertTrue(client.jsonBody.contains("\"templateId\":\"order.approved\""));
        assertTrue(client.jsonBody.contains("\"destination\":\"13800138000\""));
        assertTrue(client.jsonBody.contains("\"from\":\"Subjex\""));
        assertTrue(client.jsonBody.contains("\"otp\":\"123456\""));
    }

    @Test
    void transportFailureDoesNotLeakTheBodyOrApiKey() {
        RecordingClient client = new RecordingClient();
        client.failWith = new RuntimeException(
                "upstream said destination=13800138000 apiKey=test-api-key body={\"otp\":\"123456\"}");
        HttpNotificationChannelAdapter adapter = new HttpNotificationChannelAdapter(
                NotificationChannel.EMAIL,
                client,
                "https://notify.example.invalid/v1/email",
                "test-api-key",
                null);
        NotificationChannelSendFailedException ex = assertThrows(
                NotificationChannelSendFailedException.class,
                () -> adapter.send(request("ada@example.com", "123456")));
        assertEquals(NotificationChannelSendFailedException.REASON, ex.getMessage());
        assertNull(ex.getCause());
        assertFalse(ex.getMessage().contains("123456"));
        assertFalse(ex.getMessage().contains("test-api-key"));
        assertFalse(ex.getMessage().contains("ada@example.com"));
    }

    @Test
    void maskDestinationHidesPhoneAndEmailLocalPart() {
        assertEquals("*******8000", JdbcNotificationDispatcher.maskDestination("13800138000"));
        assertEquals("****@example.com", JdbcNotificationDispatcher.maskDestination("ada@example.com"));
        assertEquals(
                "SMS:order.approved:*******8000",
                JdbcNotificationDispatcher.actionTarget(request("13800138000", "SECRET")));
        assertFalse(JdbcNotificationDispatcher.actionTarget(request("13800138000", "SECRET")).contains("SECRET"));
        assertEquals(
                "SMS:order.approved:subject:" + JdbcNotificationDispatcher.maskDestination("end-user-ada"),
                JdbcNotificationDispatcher.actionTarget(new NotificationRequested(
                        "evt-1",
                        "tenant-alpha",
                        "order.approved",
                        NotificationChannel.SMS,
                        NotificationRecipient.ofSubject(InboxRecipientRealm.END_USER, "end-user-ada"),
                        Map.of("otp", "SECRET"))));
    }

    private static NotificationRequested request(String destination, String otp) {
        return new NotificationRequested(
                "evt-1",
                "tenant-alpha",
                "order.approved",
                destination.contains("@") ? NotificationChannel.EMAIL : NotificationChannel.SMS,
                NotificationRecipient.ofDestination(destination),
                Map.of("otp", otp));
    }

    private static final class RecordingClient implements NotificationHttpClient {
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
