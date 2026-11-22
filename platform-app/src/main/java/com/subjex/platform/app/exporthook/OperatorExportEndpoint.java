package com.subjex.platform.app.exporthook;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.identity.enduser.EndUserExportEndpoint.DownloadLinkRequest;
import com.subjex.platform.app.identity.enduser.EndUserExportEndpoint.ExportDocument;
import com.subjex.platform.app.storage.IssuedDownloadLink;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import java.time.Duration;
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
 * OperatorExportEndpoint — operator export request, approval, generate, and signed download under {@code /api/v1/exports}.
 * 操作员代申请、批准、同步生成与短时签名下载。生成与操作员下载复用 {@code export.approve}。
 */
@RestController
public class OperatorExportEndpoint {

    public static final String PATH = JsonApi.BASE + "/exports";

    private final ExportRequestService exports;
    private final ExportGenerationService generation;

    /**
     * Single constructor so Spring can wire the bean; {@code generation} may be null in unit tests that only cover request/approval.
     * 只保留一个构造器，避免 Spring 因重载而找不到默认构造并拒绝启动；单元测试若只覆盖申请/批准，可将 generation 传 null。
     */
    public OperatorExportEndpoint(ExportRequestService exports, ExportGenerationService generation) {
        this.exports = Objects.requireNonNull(exports, "exports");
        this.generation = generation;
    }

    @GetMapping(PATH)
    public List<ExportDocument> list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        OperatorPrincipal operator = requireOperator();
        return exports.listTenant(operator, resolveTenant(tenantHeader, tenantIdParam)).stream()
                .map(ExportDocument::from)
                .toList();
    }

    @PostMapping(PATH)
    public ExportDocument create(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) CreateOnBehalfRequest body) {
        OperatorPrincipal operator = requireOperator();
        String subjectId = body == null ? null : body.subjectId();
        String scope = body == null ? null : body.scope();
        return ExportDocument.from(
                exports.requestAsOperator(operator, resolveTenant(tenantHeader, tenantIdParam), subjectId, scope));
    }

    @GetMapping(PATH + "/{requestId}")
    public ExportDocument get(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("requestId") String requestId) {
        OperatorPrincipal operator = requireOperator();
        return ExportDocument.from(
                exports.getForOperator(operator, resolveTenant(tenantHeader, tenantIdParam), requestId));
    }

    @PostMapping(PATH + "/{requestId}/approve")
    public ExportDocument approve(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("requestId") String requestId) {
        OperatorPrincipal operator = requireOperator();
        return ExportDocument.from(
                exports.approve(operator, resolveTenant(tenantHeader, tenantIdParam), requestId));
    }

    @PostMapping(PATH + "/{requestId}/reject")
    public ExportDocument reject(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("requestId") String requestId) {
        OperatorPrincipal operator = requireOperator();
        return ExportDocument.from(
                exports.reject(operator, resolveTenant(tenantHeader, tenantIdParam), requestId));
    }

    @PostMapping(PATH + "/{requestId}/generate")
    public ExportDocument generate(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("requestId") String requestId) {
        if (generation == null) {
            throw new IllegalStateException("export generation is not configured");
        }
        OperatorPrincipal operator = requireOperator();
        return ExportDocument.from(
                generation.generate(operator, resolveTenant(tenantHeader, tenantIdParam), requestId));
    }

    @PostMapping(PATH + "/{requestId}/download-link")
    public IssuedDownloadLink downloadLink(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("requestId") String requestId,
            @RequestBody(required = false) DownloadLinkRequest body) {
        if (generation == null) {
            throw new IllegalStateException("export generation is not configured");
        }
        OperatorPrincipal operator = requireOperator();
        Duration ttl = body == null || body.ttl() == null || body.ttl().isBlank()
                ? null
                : Duration.parse(body.ttl().trim());
        return generation.issueOperatorLink(
                operator, resolveTenant(tenantHeader, tenantIdParam), requestId, ttl);
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

    public record CreateOnBehalfRequest(String subjectId, String scope) {}
}
