package com.subjex.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * UpstreamProxyFilter — 上游代理过滤器：把非探针请求原样转到 platform-app，带回状态码与正文。
 * <p>
 * Hop-by-hop headers are dropped. Authorization and other end-to-end headers pass through.
 * 逐跳头丢弃。Authorization 等端到端头原样转发。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class UpstreamProxyFilter extends OncePerRequestFilter {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailers",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length");

    private final UpstreamSettings upstream;
    private final HttpClient httpClient;

    public UpstreamProxyFilter(UpstreamSettings upstream, HttpClient httpClient) {
        this.upstream = upstream;
        this.httpClient = httpClient;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        URI target = upstream.root().resolve(request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(30))
                    .method(request.getMethod(), bodyPublisher(request));
            Enumeration<String> names = request.getHeaderNames();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Enumeration<String> values = request.getHeaders(name);
                while (values.hasMoreElements()) {
                    builder.header(name, values.nextElement());
                }
            }
            HttpResponse<byte[]> upstreamReply =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            response.setStatus(upstreamReply.statusCode());
            upstreamReply.headers().map().forEach((headerName, headerValues) -> {
                if (HOP_BY_HOP.contains(headerName.toLowerCase(Locale.ROOT))) {
                    return;
                }
                for (String value : headerValues) {
                    response.addHeader(headerName, value);
                }
            });
            response.getOutputStream().write(upstreamReply.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "upstream interrupted");
        } catch (IOException ex) {
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "upstream unreachable");
        }
    }

    private static HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request) throws IOException {
        if ("GET".equalsIgnoreCase(request.getMethod())
                || "HEAD".equalsIgnoreCase(request.getMethod())
                || "DELETE".equalsIgnoreCase(request.getMethod())) {
            return HttpRequest.BodyPublishers.noBody();
        }
        try (InputStream in = request.getInputStream()) {
            return HttpRequest.BodyPublishers.ofByteArray(in.readAllBytes());
        }
    }
}
