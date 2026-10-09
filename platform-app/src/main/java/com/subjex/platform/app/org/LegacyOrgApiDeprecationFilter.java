package com.subjex.platform.app.org;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.organization.OrganizationApiEndpoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds deprecation headers on legacy {@code /api/v1/org/**} responses (O5).
 * Successor: {@link OrganizationApiEndpoint#PATH}.
 * 旧组织 API 响应加弃用头；继任者为 Organization API。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class LegacyOrgApiDeprecationFilter extends OncePerRequestFilter {

    public static final String LEGACY_PREFIX = JsonApi.BASE + "/org";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(LEGACY_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("Deprecation", "true");
        response.setHeader(
                "Link",
                "<" + OrganizationApiEndpoint.PATH + ">; rel=\"successor-version\"");
        response.setHeader(
                "Warning",
                "299 - \"Deprecated: use " + OrganizationApiEndpoint.PATH + " (O5 Organization ontology)\"");
        filterChain.doFilter(request, response);
    }
}
