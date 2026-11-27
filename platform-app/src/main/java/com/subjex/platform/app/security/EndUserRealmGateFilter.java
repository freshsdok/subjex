package com.subjex.platform.app.security;

import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * EndUserRealmGateFilter — end-user tokens may only call the end-user realm (WP2-5 / ADR 0014).
 * 终端令牌只能调用终端领域接口；调用操作员控制台/管理接口一律 403。
 * <p>
 * Runs after {@link BearerTokenAuthenticationFilter}. {@link EndUserPrincipal} on any path outside
 * the allowlist is refused with {@code reason=operator-authentication-required}, including operator
 * {@code permitAll} login/refresh/logout and operator IdP/OIDC routes (a Bearer must not "use"
 * those as an end-user). Public questionnaire collect, health, and retired {@code /api/v1/me/inbox} (410 only) stay reachable.
 * 在 Bearer 过滤器之后执行。允许名单之外一律拒绝，含操作员匿名登录/刷新/退出与 IdP/OIDC。
 * 公开问卷采集、健康检查，以及已退役的 {@code /api/v1/me/inbox}（仅返回 410）仍可到达。
 */
public final class EndUserRealmGateFilter extends OncePerRequestFilter {

    /** Stable 403 reason when an end-user token hits an operator-only route — 终端令牌访问操作员路由的稳定原因。 */
    public static final String REASON = "operator-authentication-required";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof EndUserPrincipal
                && !allowsEndUser(request)) {
            writeOperatorRequired(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    static boolean allowsEndUser(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isBlank()) {
            path = request.getRequestURI();
        }
        if (path == null) {
            return false;
        }
        return path.startsWith("/api/v1/end-user/")
                || path.equals("/api/v1/me/inbox")
                || path.startsWith("/api/v1/me/inbox/")
                || path.startsWith("/actuator/health")
                || path.startsWith("/api/v1/questionnaires/public/");
    }

    static void writeOperatorRequired(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"reason\":\"" + REASON + "\"}");
    }
}
