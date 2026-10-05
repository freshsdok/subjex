package com.subjex.platform.app.discovery;

import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRoster;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * ServiceCatalog — 服务名单：共享名册里的端点，加上一个直白的状态词。
 * <p>
 * Status is {@code up} only when {@link AddressProbe} says the address answers. Anything else is {@code unknown}.
 * 只有 {@link AddressProbe} 说这个地址有应答时，状态才是 {@code up}。其余都是 {@code unknown}。
 */
public final class ServiceCatalog {

    private final ServiceRoster registry;
    private final AddressProbe probe;

    public ServiceCatalog(ServiceRoster registry, AddressProbe probe) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    public void register(ServiceEndpoint endpoint) {
        registry.register(endpoint);
    }

    public List<ListedService> list() {
        return registry.endpoints().stream()
                .sorted(Comparator.comparing(ServiceEndpoint::serviceName))
                .map(endpoint -> new ListedService(
                        endpoint.serviceName(),
                        endpoint.host(),
                        endpoint.port(),
                        statusWord(probe.word(endpoint.host(), endpoint.port()))))
                .toList();
    }

    private static String statusWord(String word) {
        return AddressProbe.UP.equals(word) ? AddressProbe.UP : AddressProbe.UNKNOWN;
    }
}
