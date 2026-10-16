package com.subjex.platform.app.form.action;

import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.form.DomainActionHandler;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * registry.register — register a service endpoint via {@link ServiceCatalog}.
 * registry.register — 经 ServiceCatalog 登记服务端点。
 */
@Component
public final class RegistryRegisterDomainActionHandler implements DomainActionHandler {

    private final ServiceCatalog services;

    public RegistryRegisterDomainActionHandler(ServiceCatalog services) {
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override
    public DomainActionKey key() {
        return DomainActionKey.REGISTRY_REGISTER;
    }

    @Override
    public String apply(
            RenderedForm form, Map<String, Object> accepted, String tenantHeader, OperatorPrincipal operator) {
        String serviceName = stringValue(accepted, "serviceName");
        String host = stringValue(accepted, "host");
        int port = intValue(accepted, "port");
        services.register(new ServiceEndpoint(serviceName, host, port));
        return serviceName + "@" + host + ":" + port;
    }

    private static String stringValue(Map<String, Object> values, String name) {
        return Objects.toString(values.get(name), "");
    }

    private static int intValue(Map<String, Object> values, String name) {
        Object raw = values.get(name);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("field " + name + " must be a whole number");
    }
}
