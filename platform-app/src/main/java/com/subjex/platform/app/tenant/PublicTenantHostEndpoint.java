package com.subjex.platform.app.tenant;

import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * PublicTenantHostEndpoint — tell the end-user shell whether this request Host is a bound tenant.
 * 告诉终端壳：当前请求的 Host 是否已绑定租户。
 * <p>
 * Uses the request {@code Host} only (no query override, no {@code X-Forwarded-Host}).
 * If the API is called on a reserved platform host, {@code bound} is false — the shell should
 * keep using its existing tenant id field. This is permitAll and does not list other tenants.
 * 只认请求 {@code Host}（不可用查询参数覆盖，也不读转发头）。
 * 若打在已保留的平台主机上，{@code bound} 为 false，终端壳继续用原来的租户填写方式。此接口无需登录，也不列举其他租户。
 */
@RestController
public class PublicTenantHostEndpoint {

    public static final String PATH = com.subjex.platform.app.api.JsonApi.BASE + "/public/host-tenant";

    private final TenantHostService hosts;

    public PublicTenantHostEndpoint(TenantHostService hosts) {
        this.hosts = Objects.requireNonNull(hosts, "hosts");
    }

    @GetMapping(PATH)
    public HostTenantDocument current(jakarta.servlet.http.HttpServletRequest request) {
        List<String> found = TenantHostNames.hostsFromRequest(request);
        if (found.size() > 1) {
            throw new TenantHostAmbiguousException("ambiguous Host");
        }
        if (found.isEmpty()) {
            return new HostTenantDocument(false, TenantHostService.Kind.PLATFORM.name(), null, null);
        }
        TenantHostService.Resolution resolution = hosts.resolveNormalized(found.get(0));
        if (resolution.kind() == TenantHostService.Kind.BOUND) {
            if (resolution.tenantId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "host tenant is not available");
            }
            return new HostTenantDocument(true, resolution.kind().name(), resolution.hostName(), resolution.tenantId());
        }
        return new HostTenantDocument(false, resolution.kind().name(), resolution.hostName(), null);
    }

    public record HostTenantDocument(boolean bound, String kind, String hostName, String tenantId) {}
}
