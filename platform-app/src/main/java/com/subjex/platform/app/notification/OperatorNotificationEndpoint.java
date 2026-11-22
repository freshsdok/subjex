package com.subjex.platform.app.notification;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorNotificationEndpoint — read-only notification send records under {@code /api/v1/notification-sends}.
 * 操作员只读外部通知发送记录；无发送、重试或重放路由。重放仍走已有死信页。
 */
@RestController
public class OperatorNotificationEndpoint {

    /** JSON base for operator notification send queries — 操作员通知发送记录 JSON 根路径. */
    public static final String PATH = JsonApi.BASE + "/notification-sends";

    private final OperatorNotificationService sends;

    /**
     * Single constructor so Spring can wire the bean.
     * 只保留一个构造器，避免 Spring 因重载而拒绝启动。
     */
    public OperatorNotificationEndpoint(OperatorNotificationService sends) {
        this.sends = Objects.requireNonNull(sends, "sends");
    }

    @GetMapping(PATH)
    public List<NotificationSendDocument> list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "channel", required = false) String channel,
            @RequestParam(value = "recipient", required = false) String recipient,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return sends.list(
                requireOperator(),
                resolveTenant(tenantHeader, tenantIdParam),
                status,
                channel,
                recipient,
                from,
                to,
                limit);
    }

    @GetMapping(PATH + "/{eventId}")
    public NotificationSendDocument get(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("eventId") String eventId) {
        return sends.get(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), eventId);
    }

    private static OperatorPrincipal requireOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof OperatorPrincipal operator) {
            return operator;
        }
        throw new org.springframework.security.access.AccessDeniedException("operator-authentication-required");
    }

    private static String resolveTenant(String header, String param) {
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (param != null && !param.isBlank()) {
            return param.trim();
        }
        return null;
    }
}
