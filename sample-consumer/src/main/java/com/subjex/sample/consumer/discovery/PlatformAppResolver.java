package com.subjex.sample.consumer.discovery;

import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRegistry;

/**
 * PlatformAppResolver — 平台应用解析：示例消费者按服务名取得 platform-app 的端点。
 * <p>
 * A separate process has an empty in-process registry, so the static configuration fallback answers.
 * When the same registry already holds a registration, that registration wins.
 * 独立进程的进程内登记簿是空的，因此由静态配置兜底回答。
 * 同一登记簿里已经有登记时，以登记为准。
 */
public final class PlatformAppResolver {

    private final ServiceEndpoint platformApp;

    public PlatformAppResolver(ServiceRegistry registry) {
        this.platformApp = registry
                .resolve(PlatformServiceNames.PLATFORM_APP)
                .orElseThrow(() -> new IllegalStateException(
                        "platform-app is not registered and static config has no fallback"));
    }

    /**
     * @return the resolved platform-app endpoint / 解析到的 platform-app 端点
     */
    public ServiceEndpoint platformApp() {
        return platformApp;
    }
}
