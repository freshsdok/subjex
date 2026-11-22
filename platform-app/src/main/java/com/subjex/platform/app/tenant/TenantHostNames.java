package com.subjex.platform.app.tenant;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * TenantHostNames — normalize and classify a Host value for tenant binding and resolution.
 * 主机名规范化与分类，用于绑定和按 Host 解析租户。
 * <p>
 * This cut reads the {@code Host} header only. It does not honor {@code X-Forwarded-Host}
 * or the {@code Forwarded} header, because platform-app has no trusted-proxy list for those
 * (unlike entry-gateway's handling of {@code X-Forwarded-For}). An edge in front should
 * forward the original public hostname as {@code Host} (the Nginx recipe already sets
 * {@code proxy_set_header Host $host}).
 * 本切口只读 {@code Host} 头，不采信 {@code X-Forwarded-Host} 或 {@code Forwarded}：
 * 平台应用没有针对这些头的可信代理名单。前面的边缘应将原来的公开主机名放进 {@code Host}。
 */
public final class TenantHostNames {

    public static final int MAX_LENGTH = 253;

    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern IPV4 = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");

    private TenantHostNames() {}

    /**
     * Lower-case host without port or trailing dot; empty becomes {@code null}.
     * 转小写、去掉端口与末尾点；空则 {@code null}。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        if (value.contains("://") || value.contains("/") || value.contains("\\")
                || value.contains("@") || value.contains("?") || value.contains("#") || value.contains("*")) {
            return null;
        }
        if (!value.startsWith("[") && value.contains(" ")) {
            return null;
        }
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close <= 1) {
                return null;
            }
            String ip = value.substring(1, close).trim();
            String rest = value.substring(close + 1);
            if (!rest.isEmpty() && !rest.startsWith(":")) {
                return null;
            }
            return ip.isEmpty() ? null : ip;
        }
        int colon = value.indexOf(':');
        if (colon == 0) {
            // Bare IPv6 such as ::1 — 形如 ::1 的裸 IPv6。
            return value;
        }
        if (colon > 0) {
            // A second colon means IPv6 without brackets — 第二个冒号表示未加括号的 IPv6。
            if (value.indexOf(':', colon + 1) >= 0) {
                return value;
            }
            value = value.substring(0, colon);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    public static boolean isIpLiteral(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return IPV4.matcher(host).matches();
    }

    public static boolean isValidDnsHostname(String host) {
        if (host == null || host.length() > MAX_LENGTH || isIpLiteral(host) || host.contains(" ")) {
            return false;
        }
        String[] labels = host.split("\\.");
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!DNS_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Built-in platform names that can never be bound to a business tenant.
     * 永远不能绑到业务租户的内置平台名（回环与单段 localhost）。
     */
    public static boolean isBuiltInPlatformHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        if (isIpLiteral(host)) {
            return true;
        }
        return "localhost".equals(host);
    }

    public static Set<String> reservedHosts(String reservedList) {
        LinkedHashSet<String> reserved = new LinkedHashSet<>();
        reserved.add("localhost");
        reserved.add("127.0.0.1");
        reserved.add("::1");
        reserved.add("0:0:0:0:0:0:0:1");
        if (reservedList != null && !reservedList.isBlank()) {
            for (String part : reservedList.split(",")) {
                String normalized = normalize(part);
                if (normalized != null) {
                    reserved.add(normalized);
                }
            }
        }
        return Set.copyOf(reserved);
    }

    public static boolean isReservedPlatformHost(String host, Collection<String> reserved) {
        if (isBuiltInPlatformHost(host)) {
            return true;
        }
        return reserved != null && reserved.contains(host);
    }

    /**
     * Read distinct {@code Host} values from the request; never {@code X-Forwarded-Host}.
     * 从请求读取互不相同的 {@code Host} 值；从不读 {@code X-Forwarded-Host}。
     */
    public static List<String> hostsFromRequest(HttpServletRequest request) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        if (request == null) {
            return List.of();
        }
        Enumeration<String> headers = request.getHeaders("Host");
        if (headers != null) {
            while (headers.hasMoreElements()) {
                String normalized = normalize(headers.nextElement());
                if (normalized != null) {
                    distinct.add(normalized);
                }
            }
        }
        if (distinct.isEmpty()) {
            String fallback = normalize(request.getHeader("Host"));
            if (fallback != null) {
                distinct.add(fallback);
            }
        }
        return new ArrayList<>(distinct);
    }

    public static String requireSingleHost(List<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            throw new TenantHostAmbiguousException("missing Host");
        }
        if (hosts.size() > 1) {
            throw new TenantHostAmbiguousException("ambiguous Host");
        }
        return hosts.get(0);
    }
}
