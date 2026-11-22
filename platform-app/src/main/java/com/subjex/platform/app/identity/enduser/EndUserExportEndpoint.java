package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.exporthook.ExportGenerationService;
import com.subjex.platform.app.exporthook.ExportRequestService;
import com.subjex.platform.app.storage.IssuedDownloadLink;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.exporthook.ExportRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * EndUserExportEndpoint — own personal-data export requests and signed download links under {@code /api/v1/end-user/exports}.
 * 终端本人数据导出申请与短时签名下载链接。
 */
@RestController
public class EndUserExportEndpoint {

    public static final String PATH = JsonApi.BASE + "/end-user/exports";

    private final ExportRequestService exports;
    private final ExportGenerationService generation;

    /**
     * Single constructor so Spring can wire the bean; {@code generation} may be null in unit tests that only cover request/approval.
     * 只保留一个构造器，避免 Spring 因重载而找不到默认构造并拒绝启动；单元测试若只覆盖申请/批准，可将 generation 传 null。
     */
    public EndUserExportEndpoint(ExportRequestService exports, ExportGenerationService generation) {
        this.exports = Objects.requireNonNull(exports, "exports");
        this.generation = generation;
    }

    @GetMapping(PATH)
    public List<ExportDocument> list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        return exports.listOwn(principal, resolveTenant(tenantHeader, tenantIdParam)).stream()
                .map(ExportDocument::from)
                .toList();
    }

    @PostMapping(PATH)
    public ExportDocument create(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) CreateRequest body) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        String scope = body == null ? null : body.scope();
        return ExportDocument.from(exports.requestAsEndUser(principal, resolveTenant(tenantHeader, tenantIdParam), scope));
    }

    @GetMapping(PATH + "/{requestId}")
    public ExportDocument get(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("requestId") String requestId) {
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        return ExportDocument.from(
                exports.getOwn(principal, resolveTenant(tenantHeader, tenantIdParam), requestId));
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
        EndUserPrincipal principal = EndUserPrincipals.requireEndUserPrincipal();
        Duration ttl = body == null || body.ttl() == null || body.ttl().isBlank()
                ? null
                : Duration.parse(body.ttl().trim());
        return generation.issueEndUserLink(
                principal, resolveTenant(tenantHeader, tenantIdParam), requestId, ttl);
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

    public record CreateRequest(String scope) {}

    public record DownloadLinkRequest(String ttl) {}

    public record ExportDocument(
            String requestId,
            String tenantId,
            String subjectId,
            String requesterRealm,
            String requesterId,
            String scope,
            String status,
            String approverSubjectId,
            String workflowInstanceId,
            Instant createdAt,
            Instant updatedAt,
            Instant decidedAt,
            Instant expiresAt,
            String artifactObjectKey) {
        public static ExportDocument from(ExportRequest request) {
            return new ExportDocument(
                    request.requestId(),
                    request.tenantId(),
                    request.subjectId(),
                    request.requesterRealm().name(),
                    request.requesterId(),
                    request.scopeDescriptor(),
                    request.status().name(),
                    request.approverSubjectId(),
                    request.workflowInstanceId(),
                    request.createdAt(),
                    request.updatedAt(),
                    request.decidedAt(),
                    request.expiresAt(),
                    request.artifactObjectKey());
        }
    }
}
