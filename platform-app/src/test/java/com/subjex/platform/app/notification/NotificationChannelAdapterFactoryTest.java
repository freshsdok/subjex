package com.subjex.platform.app.notification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationChannelAdapter;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * NotificationChannelAdapterFactoryTest — explicit adapter selection stays fail-closed by default.
 * 渠道适配器须显式配置，默认失败关闭；缺配不得回退到模拟适配器。
 */
class NotificationChannelAdapterFactoryTest {

    private static final String[] PROD = {"prod"};
    private static final String[] LOCAL = {"local"};
    private static final String[] NONE = {};
    private static final RecordingClient HTTP = new RecordingClient();
    private static final RecordingNotificationSink SINK = new RecordingNotificationSink();

    @Test
    void productionDefaultIsFailClosedForEveryChannel() {
        Map<NotificationChannel, NotificationChannelAdapter> adapters =
                NotificationChannelAdapterFactory.index(
                        NotificationChannelAdapterFactory.createAll(PROD, Map.of(), HTTP, SINK));
        for (NotificationChannel channel : NotificationChannel.values()) {
            assertInstanceOf(FailClosedNotificationAdapter.class, adapters.get(channel));
            assertFalse(adapters.get(channel).configured());
        }
    }

    @Test
    void productionRefusesMockAdapter() {
        assertThrows(
                IllegalStateException.class,
                () -> NotificationChannelAdapterFactory.create(
                        NotificationChannel.SMS,
                        PROD,
                        new NotificationChannelSettings("mock", "", "", ""),
                        HTTP,
                        SINK));
        assertThrows(
                IllegalStateException.class,
                () -> NotificationChannelAdapterFactory.create(
                        NotificationChannel.EMAIL,
                        new String[] {"production"},
                        new NotificationChannelSettings("dev", "", "", ""),
                        HTTP,
                        SINK));
    }

    @Test
    void productionHttpWithoutCompleteConfigStaysFailClosed() {
        assertInstanceOf(
                FailClosedNotificationAdapter.class,
                NotificationChannelAdapterFactory.create(
                        NotificationChannel.SMS,
                        PROD,
                        new NotificationChannelSettings("http", "", "", ""),
                        HTTP,
                        SINK));
        assertInstanceOf(
                FailClosedNotificationAdapter.class,
                NotificationChannelAdapterFactory.create(
                        NotificationChannel.SMS,
                        PROD,
                        new NotificationChannelSettings(
                                "http", "https://notify.example.invalid/v1/sms", "", ""),
                        HTTP,
                        SINK));
        assertInstanceOf(
                FailClosedNotificationAdapter.class,
                NotificationChannelAdapterFactory.create(
                        NotificationChannel.SMS,
                        PROD,
                        new NotificationChannelSettings(
                                "http", "https://notify.example.invalid/v1/sms", "change-me", ""),
                        HTTP,
                        SINK));
        assertInstanceOf(
                FailClosedNotificationAdapter.class,
                NotificationChannelAdapterFactory.create(
                        NotificationChannel.SMS,
                        PROD,
                        new NotificationChannelSettings(
                                "http", "https://notify.example.invalid/v1/sms", "test-api-key", ""),
                        null,
                        SINK));
    }

    @Test
    void productionHttpWithCompleteConfigSelectsTheTemplate() {
        NotificationChannelAdapter adapter = NotificationChannelAdapterFactory.create(
                NotificationChannel.WECHAT_TEMPLATE,
                PROD,
                new NotificationChannelSettings(
                        "http", "https://notify.example.invalid/v1/wechat", "test-api-key", ""),
                HTTP,
                SINK);
        assertInstanceOf(HttpNotificationChannelAdapter.class, adapter);
        assertTrue(adapter.configured());
        assertEqualsChannel(NotificationChannel.WECHAT_TEMPLATE, adapter);
    }

    @Test
    void nonProductionEmptyOrMockUsesMockAdapter() {
        assertInstanceOf(
                MockNotificationAdapter.class,
                NotificationChannelAdapterFactory.create(
                        NotificationChannel.EMAIL,
                        NONE,
                        NotificationChannelSettings.empty(),
                        HTTP,
                        SINK));
        assertInstanceOf(
                MockNotificationAdapter.class,
                NotificationChannelAdapterFactory.create(
                        NotificationChannel.EMAIL,
                        LOCAL,
                        new NotificationChannelSettings("mock", "", "", ""),
                        HTTP,
                        SINK));
    }

    @Test
    void explicitFailClosedWinsInNonProduction() {
        assertInstanceOf(
                FailClosedNotificationAdapter.class,
                NotificationChannelAdapterFactory.create(
                        NotificationChannel.SMS,
                        LOCAL,
                        new NotificationChannelSettings("fail-closed", "", "", ""),
                        HTTP,
                        SINK));
    }

    @Test
    void explicitHttpDoesNotFallBackToMockWhenIncomplete() {
        NotificationChannelAdapter adapter = NotificationChannelAdapterFactory.create(
                NotificationChannel.SMS,
                LOCAL,
                new NotificationChannelSettings("http", "https://notify.example.invalid/v1/sms", "", ""),
                HTTP,
                SINK);
        assertInstanceOf(FailClosedNotificationAdapter.class, adapter);
        assertFalse(adapter.configured());
    }

    @Test
    void unknownAdapterIsFailClosed() {
        assertInstanceOf(
                FailClosedNotificationAdapter.class,
                NotificationChannelAdapterFactory.create(
                        NotificationChannel.SMS,
                        PROD,
                        new NotificationChannelSettings("unknown", "", "", ""),
                        HTTP,
                        SINK));
        assertInstanceOf(
                FailClosedNotificationAdapter.class,
                NotificationChannelAdapterFactory.create(
                        NotificationChannel.SMS,
                        LOCAL,
                        new NotificationChannelSettings("unknown", "", "", ""),
                        HTTP,
                        SINK));
    }

    private static void assertEqualsChannel(NotificationChannel expected, NotificationChannelAdapter adapter) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, adapter.channel());
    }

    private static final class RecordingClient implements NotificationHttpClient {
        @Override
        public void post(String endpoint, String apiKey, String jsonBody) {
            throw new IllegalStateException("factory tests must not send");
        }
    }
}
