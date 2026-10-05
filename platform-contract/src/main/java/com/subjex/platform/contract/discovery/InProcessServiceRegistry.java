package com.subjex.platform.contract.discovery;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InProcessServiceRegistry — 进程内登记簿：只在本 JVM 里记住端点。
 * <p>
 * Another process has its own map. This class does not talk to a registry server.
 * 另一个进程有自己的表。这个类不连接注册中心服务器。
 */
public final class InProcessServiceRegistry implements ServiceRegistry {

    private final ConcurrentHashMap<String, ServiceEndpoint> endpoints = new ConcurrentHashMap<>();

    @Override
    public void register(ServiceEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        endpoints.put(endpoint.serviceName(), endpoint);
    }

    @Override
    public Optional<ServiceEndpoint> resolve(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(endpoints.get(serviceName));
    }
}
