package com.subjex.platform.app.discovery;

import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * PlatformSelfRegistrar — 平台自登记：宿主进程把自己的端点写入本地登记表。
 * <p>
 * The address is this process's advertised host and HTTP port.
 * The write lands in the local table that the HTTP registry publishes. It is not learned from Nacos.
 * 地址是本进程公布的主机和 HTTP 端口。
 * 写入落在本地表里，再由 HTTP 登记公布出去。不是从 Nacos 学来的。
 */
public final class PlatformSelfRegistrar implements ApplicationRunner {

    private final ServiceRegistry registry;
    private final String host;
    private final int port;

    public PlatformSelfRegistrar(ServiceRegistry registry, String host, int port) {
        this.registry = registry;
        this.host = host;
        this.port = port;
    }

    @Override
    public void run(ApplicationArguments args) {
        registry.register(new ServiceEndpoint(PlatformServiceNames.PLATFORM_APP, host, port));
    }
}
