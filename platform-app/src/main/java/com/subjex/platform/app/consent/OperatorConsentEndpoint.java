package com.subjex.platform.app.consent;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.identity.enduser.EndUserConsentEndpoint.CurrentDocument;
import com.subjex.platform.app.identity.enduser.EndUserConsentEndpoint.HistoryDocument;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorConsentEndpoint — read-only consent list and history under {@code /api/v1/consents}.
 * 操作员只读同意列表与历史；无授予或撤回路由，不能代用户作出决定。
 */
@RestController
public class OperatorConsentEndpoint {

    /** JSON base for operator consent queries — 操作员同意查询 JSON 根路径. */
    public static final String PATH = JsonApi.BASE + "/consents";

    private final OperatorConsentService consents;

    public OperatorConsentEndpoint(OperatorConsentService consents) {
        this.consents = Objects.requireNonNull(consents, "consents");
    }

    @GetMapping(PATH)
    public List<CurrentDocument> listCurrent(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "subjectId", required = false) String subjectId,
            @RequestParam(value = "purposeKey", required = false) String purposeKey,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to) {
        return consents
                .listCurrent(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), subjectId, purposeKey, from, to)
                .stream()
                .map(CurrentDocument::from)
                .toList();
    }

    @GetMapping(PATH + "/history")
    public List<HistoryDocument> history(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "subjectId", required = false) String subjectId,
            @RequestParam(value = "purposeKey", required = false) String purposeKey,
            @RequestParam(value = "from", required = false) Instant from,
            @RequestParam(value = "to", required = false) Instant to) {
        return consents
                .history(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), subjectId, purposeKey, from, to)
                .stream()
                .map(HistoryDocument::from)
                .toList();
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
