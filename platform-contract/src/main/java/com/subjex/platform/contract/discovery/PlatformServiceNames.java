package com.subjex.platform.contract.discovery;

/**
 * PlatformServiceNames — 平台服务名：两个进程在发现端口上使用的稳定名字。
 * <p>
 * {@code platform-app} is the host process. {@code sample-consumer} is the second process.
 * {@code platform-app} 是宿主进程。{@code sample-consumer} 是第二个进程。
 */
public final class PlatformServiceNames {

    /** Host process — 宿主进程。 */
    public static final String PLATFORM_APP = "platform-app";

    /** Second process — 第二个进程。 */
    public static final String SAMPLE_CONSUMER = "sample-consumer";

    private PlatformServiceNames() {}
}
