package com.subjex.platform.app.admin;

import java.time.Instant;

/**
 * AuditQuery — OBS-1 filter for audit list (tenant / actor / action / time window).
 * 审计列表筛选：租户、操作者、动作、时间窗。
 */
public record AuditQuery(
        String tenantId,
        String actorLogin,
        String actionName,
        Instant from,
        Instant to,
        int limit) {

    public static final int DEFAULT_LIMIT = AuditPage.ROW_LIMIT;
    public static final int MAX_LIMIT = 500;

    public AuditQuery {
        if (limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
        tenantId = blankToNull(tenantId);
        actorLogin = blankToNull(actorLogin);
        actionName = blankToNull(actionName);
    }

    public static AuditQuery unfiltered(int limit) {
        return new AuditQuery(null, null, null, null, null, limit);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
