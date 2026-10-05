package com.subjex.platform.app.discovery;

import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRegistry;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * PlatformSelfRegistrar — 平台自登记：宿主进程把自己的端点写入进程内登记簿。
 * <p>
 * The address is this process's advertised host and HTTP port. It is not learned from a registry server.
 * 地址是本进程公布的主机和 HTTP 端口，不是从注册中心服务器学来的。
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
