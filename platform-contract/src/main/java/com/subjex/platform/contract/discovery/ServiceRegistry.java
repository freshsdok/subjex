package com.subjex.platform.contract.discovery;

import java.util.Optional;

/**
 * ServiceRegistry — 服务登记簿：登记一个端点，或按服务名解析它。
 * <p>
 * Implementations in this slice stay inside one process. They must not pretend to be a network registry.
 * 这一层的实现都留在一个进程里，不得伪装成网络注册中心。
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
