package com.subjex.sample.consumer.discovery;

import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRegistry;

/**
 * PlatformAppResolver — 平台应用解析：示例消费者按服务名取得 platform-app 的端点。
 * <p>
 * The registry is the HTTP registry on platform-app when that call connects.
 * When it cannot be reached, or it has no platform-app entry, static configuration answers.
 * 登记簿是 platform-app 上的 HTTP 登记，调用连得上时用它。
 * 连不上，或里面没有 platform-app 时，由静态配置回答。
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
