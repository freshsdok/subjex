package com.subjex.platform.app.security;

import com.subjex.platform.contract.tenant.TenantGuard;
import com.subjex.platform.contract.tenant.TenantMissingException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TenantEnforcementFilter — 租户拦截：租户作用域的请求在进入动作前先过门禁。
 * <p>
 * Probes, the read-only operator console, the service list, the config list, the deploy page, the form field list, and the generated-type page are not tenant-scoped.
 * Every other path uses {@link TenantGuard}, whose default denies a missing tenant.
 * 探针、只读操作台、服务名单、配置名单、部署清单页、字段列表页和生成类型页不属某个租户。其余路径都走 {@link TenantGuard}，默认在租户缺失时拒绝。
 */
public final class TenantEnforcementFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final TenantGuard tenantGuard;

    public TenantEnforcementFilter(TenantGuard tenantGuard) {
        this.tenantGuard = tenantGuard;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/")
                || path.startsWith("/admin/")
                || "/admin".equals(path)
                || path.startsWith("/registry/")
                || "/services".equals(path)
                || "/deploy".equals(path)
                || "/forms".equals(path)
                || "/codegen".equals(path)
                || path.startsWith("/config");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            tenantGuard.requireTenant(request.getHeader(TENANT_HEADER));
        } catch (TenantMissingException ex) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
