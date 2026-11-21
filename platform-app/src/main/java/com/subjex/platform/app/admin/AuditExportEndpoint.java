package com.subjex.platform.app.admin;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import java.time.Instant;
import java.util.Objects;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AuditExportEndpoint — operator NDJSON audit export for one granted tenant (WP9-1).
 * 操作员按已授权租户导出审计 NDJSON。
 */
@RestController
public class AuditExportEndpoint {

    public static final String PATH = JsonApi.BASE + "/audit/export";

    private static final MediaType NDJSON = MediaType.parseMediaType(AuditExportService.MEDIA_TYPE);

    private final AuditExportService exports;

    public AuditExportEndpoint(AuditExportService exports) {
        this.exports = Objects.requireNonNull(exports, "exports");
    }

    @PostMapping(PATH)
    public ResponseEntity<byte[]> export(
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = TenantEnforcementFilter.TENANT_HEADER, required = false)
                    String tenantHeader,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to,
            @RequestParam(value = "limit", required = false) Integer limit) {
        OperatorPrincipal operator = requireOperator();
        String tenantId = tenantIdParam != null && !tenantIdParam.isBlank() ? tenantIdParam : tenantHeader;
        byte[] body = exports.export(operator, tenantId, from, to, limit);
        return ResponseEntity.ok().contentType(NDJSON).body(body);
    }

    private static OperatorPrincipal requireOperator() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof OperatorPrincipal operator) {
            return operator;
        }
        throw new org.springframework.security.access.AccessDeniedException("operator-authentication-required");
    }
}
