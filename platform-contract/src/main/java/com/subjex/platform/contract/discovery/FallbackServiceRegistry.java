package com.subjex.platform.contract.discovery;

import java.util.Objects;
import java.util.Optional;

/**
 * FallbackServiceRegistry — 带回退的登记簿：主登记簿没有这个名字时，用静态配置。
 * <p>
 * {@link #register} writes only the primary registry. It does not change static configuration.
 * {@link #register} 只写主登记簿，不改静态配置。
 */
public final class FallbackServiceRegistry implements ServiceRegistry {

    private final ServiceRegistry registry;
    private final ServiceRegistry fallback;

    public FallbackServiceRegistry(ServiceRegistry registry, ServiceRegistry fallback) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public void register(ServiceEndpoint endpoint) {
        registry.register(endpoint);
    }

    @Override
    public Optional<ServiceEndpoint> resolve(String serviceName) {
        return registry.resolve(serviceName).or(() -> fallback.resolve(serviceName));
    }
}
