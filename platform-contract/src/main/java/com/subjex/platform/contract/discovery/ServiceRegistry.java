package com.subjex.platform.contract.discovery;

import java.util.Optional;

/**
 * ServiceRegistry — 服务登记簿：登记一个端点，或按服务名解析它。
 * <p>
 * The port stays these two operations. platform-app stores endpoints in the shared database and serves them over HTTP.
 * Another process calls that HTTP registry. Tests may use an in-process table. Neither is a Nacos, Eureka, or Consul server.
 * 这个端口仍然只有这两个操作。platform-app 把端点存进共享库并用 HTTP 提供。
 * 另一个进程调用那个 HTTP 登记。测试可用进程内表。两者都不是 Nacos、Eureka 或 Consul 服务器。
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
