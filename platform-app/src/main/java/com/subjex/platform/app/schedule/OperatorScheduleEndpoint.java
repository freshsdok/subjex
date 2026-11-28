package com.subjex.platform.app.schedule;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.schedule.ScheduleRecord;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorScheduleEndpoint — create, list, enable, and disable under {@code /api/v1/schedules}.
 * 操作员调度：创建、列出、启用与停用。
 */
@RestController
public class OperatorScheduleEndpoint {

    /** JSON base for operator schedules — 操作员调度 JSON 根路径. */
    public static final String PATH = JsonApi.BASE + "/schedules";

    private final OperatorScheduleService schedules;

    public OperatorScheduleEndpoint(OperatorScheduleService schedules) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
    }

    @GetMapping(PATH)
    public List<ScheduleRecord> list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return schedules.list(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), limit);
    }

    @PostMapping(PATH)
    public ScheduleRecord create(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) SaveScheduleRequest body) {
        return schedules.create(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), body);
    }

    @PostMapping(PATH + "/{scheduleId}/enable")
    public ScheduleRecord enable(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("scheduleId") String scheduleId) {
        return schedules.enable(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), scheduleId);
    }

    @PostMapping(PATH + "/{scheduleId}/disable")
    public ScheduleRecord disable(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("scheduleId") String scheduleId) {
        return schedules.disable(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), scheduleId);
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
