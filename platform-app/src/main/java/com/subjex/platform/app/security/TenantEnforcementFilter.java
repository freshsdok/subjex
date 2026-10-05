package com.subjex.platform.app.security;

import com.subjex.platform.contract.tenant.TenantGuard;
import com.subjex.platform.contract.tenant.TenantMissingException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TenantEnforcementFilter — 租户拦截：租户作用域的请求在进入动作前先过门禁与操作员—租户授权。
 * <p>
 * Probes, the read-only operator console, the audit page, the service list, the config list, the deploy page, the form field list, the generated-type page, the language page, the skin page, and every JSON twin under {@code /api/v1} are not tenant-scoped.
 * Every other path uses {@link TenantGuard} (missing tenant denied) then {@link OperatorTenantAccess}
 * (operator must be granted that tenant, or {@code *}).
 * 探针、只读操作台、审计页、服务名单、配置名单、部署清单页、字段列表页、生成类型页、语言页、外观页，以及 {@code /api/v1} 下的每个 JSON 孪生接口，都不属某个租户。
 * 其余路径先过 {@link TenantGuard}，再核对操作员—租户授权（含通配 {@code *}）。
 */
public final class TenantEnforcementFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public TenantEnforcementFilter(TenantGuard tenantGuard, OperatorTenantAccess tenantAccess) {
        this.tenantGuard = tenantGuard;
        this.tenantAccess = tenantAccess;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/")
                || path.startsWith("/admin/")
                || "/admin".equals(path)
                || path.startsWith("/registry/")
                || "/audit".equals(path)
                || "/services".equals(path)
                || "/deploy".equals(path)
                || "/forms".equals(path)
                || "/codegen".equals(path)
                || "/language".equals(path)
                || "/skin".equals(path)
                || path.startsWith("/config")
                || path.startsWith("/api/v1");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String tenantId = request.getHeader(TENANT_HEADER);
            tenantGuard.requireTenant(tenantId);
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !(authentication.getPrincipal() instanceof OperatorPrincipal operator)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            tenantAccess.requireGranted(operator, tenantId);
        } catch (TenantMissingException | OperatorTenantNotGrantedException ex) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
