package com.subjex.platform.app.discovery;

import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRegistry;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * PlatformSelfRegistrar — 平台自登记：宿主进程把自己的端点写入本地登记表。
 * <p>
 * The address is this process's advertised host and the HTTP port the web server actually bound.
 * Registration waits for the web server to start, so {@code server.port=0} registers the random port, not zero.
 * A separate management server (namespace {@code management}) is not the application endpoint and is not registered.
 * The write lands in the local table that the HTTP registry publishes. It is not learned from Nacos.
 * 地址是本进程公布的主机，以及 Web 服务器真正绑上的 HTTP 端口。
 * 登记等 Web 服务器启动之后才做，所以 {@code server.port=0} 登记的是随机端口，不是零。
 * 单独的管理端口（命名空间 {@code management}）不是应用端点，不登记。
 * 写入落在本地表里，再由 HTTP 登记公布出去。不是从 Nacos 学来的。
 */
public final class PlatformSelfRegistrar implements ApplicationListener<WebServerInitializedEvent> {

    /** Server namespace of Spring Boot's separate management server — Spring Boot 单独管理端口的服务器命名空间。 */
    static final String MANAGEMENT_SERVER_NAMESPACE = "management";

    private final ServiceRegistry registry;
    /** advertisedHost — 公布给别的进程的主机名或地址。 */
    private final String advertisedHost;

    public PlatformSelfRegistrar(ServiceRegistry registry, String advertisedHost) {
        this.registry = registry;
        this.advertisedHost = advertisedHost;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent startedServer) {
        if (MANAGEMENT_SERVER_NAMESPACE.equals(startedServer.getApplicationContext().getServerNamespace())) {
            return;
        }
        // boundPort — 已绑定端口：服务器真正监听的端口，server.port=0 时也是实际值。
        int boundPort = startedServer.getWebServer().getPort();
        registry.register(new ServiceEndpoint(PlatformServiceNames.PLATFORM_APP, advertisedHost, boundPort));
    }
}
