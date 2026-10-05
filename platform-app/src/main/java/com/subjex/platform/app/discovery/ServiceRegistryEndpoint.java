package com.subjex.platform.app.discovery;

import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.discovery.HttpServiceRegistry;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ServiceRegistryEndpoint — 登记接口：别的进程在这里登记，并读取同一份名单。
 * <p>
 * This is not a separate registry server. The human page is {@code GET /services}, not this JSON list.
 * Reading needs {@code registry.read}; registering needs {@code registry.write} and leaves one audit entry
 * ({@code registry.register}, target = service name).
 * 这不是单独的注册中心服务器。给人看的页面是 {@code GET /services}，不是这份 JSON 名单。
 * 读取需要 {@code registry.read}；登记需要 {@code registry.write}，并留下一条审计（{@code registry.register}，对象是服务名）。
 */
@RestController
@RequestMapping(HttpServiceRegistry.SERVICES_PATH)
public class ServiceRegistryEndpoint {

    private final ServiceCatalog catalog;
    private final OperatorActionAudit audit;

    public ServiceRegistryEndpoint(ServiceCatalog catalog, OperatorActionAudit audit) {
        this.catalog = catalog;
        this.audit = audit;
    }

    @PostMapping
    public ResponseEntity<Void> register(
            @RequestBody ServiceRegistration registration, @AuthenticationPrincipal OperatorPrincipal operator) {
        if (registration == null) {
            throw new IllegalArgumentException("registration is missing");
        }
        catalog.register(new ServiceEndpoint(registration.serviceName(), registration.host(), registration.port()));
        audit.record(operator, OperatorActionAudit.REGISTRY_REGISTER, registration.serviceName(), AuditOutcome.ALLOWED);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<ListedService> list() {
        return catalog.list();
    }
}
