package com.subjex.platform.contract.discovery;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InProcessServiceRegistry — 进程内登记表：只在本 JVM 的一张表里记住端点。
 * <p>
 * The class itself does not open a socket. platform-app publishes this table through its HTTP registry.
 * Another process does not read this object; it calls that HTTP registry.
 * platform-app now wires the JDBC roster; this table is for tests and for an empty fail-closed registry.
 * 这个类自己不开套接字。platform-app 通过自己的 HTTP 登记把这张表公布出去。
 * 另一个进程不读这个对象，它调用那个 HTTP 登记。
 * platform-app 现在装配 JDBC 名册；这张表用于测试，以及作为失败关闭的空登记簿。
 */
public final class InProcessServiceRegistry implements ServiceRoster {

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

    /**
     * @return the endpoints currently in this table / 当前表里的端点
     */
    @Override
    public List<ServiceEndpoint> endpoints() {
        return List.copyOf(endpoints.values());
    }
}
