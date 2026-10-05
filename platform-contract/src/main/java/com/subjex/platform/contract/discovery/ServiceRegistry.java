package com.subjex.platform.contract.discovery;

import java.util.Optional;

/**
 * ServiceRegistry — 服务登记簿：登记一个端点，或按服务名解析它。
 * <p>
 * The port stays these two operations. One implementation keeps endpoints in this JVM.
 * Another calls the HTTP registry hosted by platform-app. Neither is a Nacos, Eureka, or Consul server.
 * 这个端口仍然只有这两个操作。一个实现把端点留在本 JVM。
 * 另一个调用 platform-app 提供的 HTTP 登记。两者都不是 Nacos、Eureka 或 Consul 服务器。
 */
public interface ServiceRegistry {

    /**
     * Remember this endpoint under its service name — 按服务名记住这个端点。
     */
    void register(ServiceEndpoint endpoint);

    /**
     * @return the endpoint for this service name, when the registry knows it
     *         登记簿知道这个服务名时，返回它的端点
     */
    Optional<ServiceEndpoint> resolve(String serviceName);
}
