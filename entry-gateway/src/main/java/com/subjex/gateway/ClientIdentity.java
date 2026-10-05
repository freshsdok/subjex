package com.subjex.gateway;

import jakarta.servlet.http.HttpServletRequest;

/**
 * ClientIdentity — 客户端标识：优先取 {@code X-Forwarded-For} 的第一段，否则用远端地址。
 * <p>
 * Coarse only. It is not a substitute for authenticated operator identity.
 * 只做粗粒度区分。它不能代替已认证的操作员身份。
 */
public final class ClientIdentity {

    private ClientIdentity() {}

    public static String from(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
