package com.subjex.platform.contract.discovery;

import com.subjex.platform.contract.config.ConfigSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * StaticServiceFallback — 静态配置兜底：登记簿里没有名字时，用本地配置里的主机和端口。
 * <p>
 * Registration is refused. Static configuration is not a place that accepts live registrations.
 * 拒绝登记。静态配置不接受运行时登记。
 */
public final class StaticServiceFallback implements ServiceRegistry {

    private final Map<String, ServiceEndpoint> endpoints;

    private StaticServiceFallback(Map<String, ServiceEndpoint> endpoints) {
        this.endpoints = Map.copyOf(endpoints);
    }

    /**
     * Read {@code platform.discovery.static.<service>.host} and {@code .port}.
     * A service with neither key is skipped. One key without the other is rejected.
     * 读取 {@code platform.discovery.static.<service>.host} 和 {@code .port}。
     * 两个键都没有的服务被跳过。只有其中一个键则拒绝。
     */
    public static StaticServiceFallback fromConfig(ConfigSource source, String... serviceNames) {
        Map<String, ServiceEndpoint> endpoints = new LinkedHashMap<>();
        for (String serviceName : serviceNames) {
            Optional<String> host = source.lookup(hostKey(serviceName));
            Optional<String> port = source.lookup(portKey(serviceName));
            if (host.isEmpty() && port.isEmpty()) {
                continue;
            }
            if (host.isEmpty() || port.isEmpty()) {
                throw new IllegalArgumentException("static endpoint " + serviceName + " needs both host and port");
            }
            int parsed;
            try {
                parsed = Integer.parseInt(port.get());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("static endpoint " + serviceName + " has a port that is not an integer");
            }
            endpoints.put(serviceName, new ServiceEndpoint(serviceName, host.get(), parsed));
        }
        return new StaticServiceFallback(endpoints);
    }

    public static String hostKey(String serviceName) {
        return "platform.discovery.static." + serviceName + ".host";
    }

    public static String portKey(String serviceName) {
        return "platform.discovery.static." + serviceName + ".port";
    }

    @Override
    public void register(ServiceEndpoint endpoint) {
        throw new UnsupportedOperationException("static config does not accept registration");
    }

    @Override
    public Optional<ServiceEndpoint> resolve(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(endpoints.get(serviceName));
    }
}
