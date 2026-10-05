package com.subjex.gateway;

import com.subjex.platform.contract.ratelimit.RateLimitPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * GatewayRateLimitFilter — 网关限流过滤器：探针放行，其余请求按客户端标识计数，超限回 429。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GatewayRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitPort rateLimitPort;

    public GatewayRateLimitFilter(RateLimitPort rateLimitPort) {
        this.rateLimitPort = rateLimitPort;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String clientId = ClientIdentity.from(request);
        if (!rateLimitPort.permit(clientId, GatewayWiring.HTTP_ACTION)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"reason\":\"rate-limited\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
