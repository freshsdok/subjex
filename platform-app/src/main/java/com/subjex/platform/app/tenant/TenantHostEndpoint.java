package com.subjex.platform.app.tenant;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * TenantHostEndpoint — list/add/remove custom hostnames for a business tenant.
 * 列出、添加、移除某个业务租户的自定义主机名。
 * <p>
 * List needs {@code admin.read}. Writes need {@code tenant.manage}. This does not issue DNS or certificates.
 * 列表要 {@code admin.read}。写入要 {@code tenant.manage}。这里不办理 DNS，也不签发证书。
 */
@RestController
public class TenantHostEndpoint {

    public static final String PATH = JsonApi.BASE + "/tenants/{tenantId}/hosts";

    private final TenantHostService hosts;

    public TenantHostEndpoint(TenantHostService hosts) {
        this.hosts = Objects.requireNonNull(hosts, "hosts");
    }

    @GetMapping(PATH)
    public HostsDocument list(@PathVariable("tenantId") String tenantId) {
        try {
            List<HostDocument> rows = hosts.list(tenantId).stream().map(TenantHostEndpoint::toDocument).toList();
            return new HostsDocument(tenantId, rows);
        } catch (IllegalArgumentException ex) {
            throw mapClientError(ex);
        }
    }

    @PostMapping(PATH)
    @ResponseStatus(HttpStatus.CREATED)
    public HostDocument bind(@PathVariable("tenantId") String tenantId, @RequestBody BindHostRequest body) {
        try {
            return toDocument(hosts.bind(requireOperator(), tenantId, body == null ? null : body.hostName()));
        } catch (IllegalArgumentException ex) {
            throw mapClientError(ex);
        }
    }

    @DeleteMapping(PATH + "/{hostName:.+}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable("tenantId") String tenantId, @PathVariable("hostName") String hostName) {
        try {
            boolean removed = hosts.unbind(requireOperator(), tenantId, hostName);
            if (!removed) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "host not bound to this tenant");
            }
        } catch (IllegalArgumentException ex) {
            throw mapClientError(ex);
        }
    }

    private static OperatorPrincipal requireOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof OperatorPrincipal operator) {
            return operator;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private static ResponseStatusException mapClientError(IllegalArgumentException ex) {
        String message = ex.getMessage() == null ? "invalid host" : ex.getMessage();
        if (message.contains("tenant missing")) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static HostDocument toDocument(TenantHostService.Binding row) {
        return new HostDocument(row.hostName(), row.tenantId(), row.createdAt());
    }

    public record HostsDocument(String tenantId, List<HostDocument> hosts) {}

    public record HostDocument(String hostName, String tenantId, Instant createdAt) {}

    public record BindHostRequest(String hostName) {}
}
