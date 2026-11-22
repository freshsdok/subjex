package com.subjex.platform.app.tenant;

import com.subjex.platform.app.security.TenantEnforcementFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TenantHostResolutionFilter — when Host is a bound tenant domain, fill {@code X-Tenant-Id}.
 * 当 Host 是已绑定的租户域名时，补上 {@code X-Tenant-Id}。
 * <p>
 * Conflicts (another tenant in the header, or several Host values) fail closed. Unknown hosts
 * do not invent a tenant; existing header-based calls keep working. Platform hosts are unchanged.
 * 与请求头中的其他租户冲突、或出现多个 Host 时失败关闭。未知主机不会编造租户；
 * 原来靠请求头的调用仍然有效。平台主机不做改动。
 * <p>
 * Does not read {@code X-Forwarded-Host}. Operator console should stay on a reserved platform host.
 * 不读 {@code X-Forwarded-Host}。操作台应继续使用已保留的平台主机名。
 */
@Order(-95)
public final class TenantHostResolutionFilter extends OncePerRequestFilter {

    private final TenantHostService hosts;

    public TenantHostResolutionFilter(TenantHostService hosts) {
        this.hosts = Objects.requireNonNull(hosts, "hosts");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/v1");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> hostsOnRequest;
        try {
            hostsOnRequest = TenantHostNames.hostsFromRequest(request);
        } catch (RuntimeException ex) {
            writeJson(response, HttpServletResponse.SC_CONFLICT, "ambiguous Host");
            return;
        }
        if (hostsOnRequest.size() > 1) {
            writeJson(response, HttpServletResponse.SC_CONFLICT, "ambiguous Host");
            return;
        }
        if (hostsOnRequest.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        TenantHostService.Resolution resolution;
        try {
            resolution = hosts.resolveNormalized(hostsOnRequest.get(0));
        } catch (TenantHostAmbiguousException ex) {
            writeJson(response, HttpServletResponse.SC_CONFLICT, safeReason(ex.getMessage(), "ambiguous Host"));
            return;
        } catch (TenantHostClosedException ex) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, safeReason(ex.getMessage(), "host tenant is not available"));
            return;
        }
        if (resolution.kind() != TenantHostService.Kind.BOUND || resolution.tenantId() == null) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(TenantEnforcementFilter.TENANT_HEADER);
        if (header != null && !header.isBlank()) {
            String declared = header.trim();
            if (!declared.equals(resolution.tenantId())) {
                writeJson(response, HttpServletResponse.SC_CONFLICT, "Host and X-Tenant-Id do not match");
                return;
            }
        }
        chain.doFilter(new TenantHeaderRequest(request, resolution.tenantId()), response);
    }

    private static void writeJson(HttpServletResponse response, int status, String reason) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String escaped = reason.replace("\\", "\\\\").replace("\"", "\\\"");
        response.getWriter().write("{\"reason\":\"" + escaped + "\"}");
    }

    private static String safeReason(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }

    private static final class TenantHeaderRequest extends HttpServletRequestWrapper {
        private final String tenantId;

        private TenantHeaderRequest(HttpServletRequest request, String tenantId) {
            super(request);
            this.tenantId = tenantId;
        }

        @Override
        public String getHeader(String name) {
            if (TenantEnforcementFilter.TENANT_HEADER.equalsIgnoreCase(name)) {
                return tenantId;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (TenantEnforcementFilter.TENANT_HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(tenantId));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>();
            Enumeration<String> existing = super.getHeaderNames();
            boolean present = false;
            if (existing != null) {
                while (existing.hasMoreElements()) {
                    String name = existing.nextElement();
                    names.add(name);
                    if (TenantEnforcementFilter.TENANT_HEADER.equalsIgnoreCase(name)) {
                        present = true;
                    }
                }
            }
            if (!present) {
                names.add(TenantEnforcementFilter.TENANT_HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
