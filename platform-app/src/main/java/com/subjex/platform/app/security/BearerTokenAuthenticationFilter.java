package com.subjex.platform.app.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * BearerTokenAuthenticationFilter — 解析 {@code Authorization: Bearer <opaque>}，写入 SecurityContext。
 * <p>
 * Runs before HTTP Basic. When the header is Bearer, Basic is not attempted on the same request
 * (Spring's Basic filter ignores non-Basic schemes). Invalid or expired Bearer leaves the context empty → 401.
 * 在 HTTP Basic 之前运行。Bearer 头存在时 Basic 不会解析该头。无效/过期 Bearer 不设认证 → 401。
 */
public final class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String BEARER_PREFIX = "Bearer ";

    private final JdbcOperatorTokenStore tokenStore;

    public BearerTokenAuthenticationFilter(JdbcOperatorTokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String raw = header.substring(BEARER_PREFIX.length()).trim();
            Optional<OperatorPrincipal> principal = tokenStore.resolveAccessToken(raw);
            if (principal.isPresent()) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        principal.get(), null, principal.get().getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }
}
