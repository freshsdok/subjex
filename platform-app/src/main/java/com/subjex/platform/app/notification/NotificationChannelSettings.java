package com.subjex.platform.app.notification;

/**
 * NotificationChannelSettings — explicit adapter choice and HTTP placeholders for one channel.
 * 单一渠道的显式适配器选择与 HTTP 占位配置。
 * <p>
 * Values are placeholders until a vendor account exists. An incomplete HTTP shape is not a live
 * vendor and must fail closed.
 * 在厂商账号到位前这些只是占位。HTTP 配置不完整不是已启用的真实渠道，必须失败关闭。
 */
public record NotificationChannelSettings(String adapter, String httpBaseUrl, String httpApiKey, String httpFrom) {

    public static NotificationChannelSettings empty() {
        return new NotificationChannelSettings("", "", "", "");
    }
}
