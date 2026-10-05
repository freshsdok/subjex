package com.subjex.gateway;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ClientIdentity — 客户端标识：默认只用远端地址；仅当远端是已配置的可信代理时才采信 {@code X-Forwarded-For}。
 * <p>
 * With {@code gateway.trusted-proxies} empty (the default) the header is ignored. When the direct peer is a
 * trusted proxy, the header is read right to left, skipping trusted hops, and the first untrusted hop is the
 * client. A client cannot pick its own id by sending the header, because entries it prepends sit to the left
 * of what the trusted proxy appended.
 * 未配置 {@code gateway.trusted-proxies}（默认）时忽略该头。直连方是可信代理时，从右往左读，跳过可信跳，
 * 第一个不可信的跳即客户端；客户端自己伪造的条目在可信代理追加的条目左边，选不中。
 * <p>
 * Coarse only. It is not a substitute for authenticated operator identity.
 * 只做粗粒度区分。它不能代替已认证的操作员身份。
 */
public final class ClientIdentity {

    static final String FORWARDED_FOR = "X-Forwarded-For";

    private final TrustedProxies trustedProxies;

    public ClientIdentity(TrustedProxies trustedProxies) {
        this.trustedProxies = trustedProxies == null ? TrustedProxies.none() : trustedProxies;
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String peer = remote == null || remote.isBlank() ? "unknown" : remote.trim();
        if (trustedProxies.isEmpty() || !trustedProxies.contains(peer)) {
            return peer;
        }
        List<String> hops = forwardedHops(request);
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (!trustedProxies.contains(hop)) {
                return hop;
            }
        }
        // Every hop is a trusted proxy: use the left-most one, or the peer when there is no header.
        return hops.isEmpty() ? peer : hops.get(0);
    }

    private static List<String> forwardedHops(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders(FORWARDED_FOR))) {
            if (header == null) {
                continue;
            }
            for (String part : header.split(",")) {
                String hop = part.trim();
                if (!hop.isEmpty()) {
                    hops.add(hop);
                }
            }
        }
        return hops;
    }
}
