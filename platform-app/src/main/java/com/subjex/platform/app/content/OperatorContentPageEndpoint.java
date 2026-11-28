package com.subjex.platform.app.content;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.content.ContentPageDocument;
import com.subjex.platform.contract.content.ContentPageSummary;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OperatorContentPageEndpoint — CRUD, publish, and unpublish under {@code /api/v1/content-pages}.
 * 操作员内容页：创建、编辑、发布与取消发布。不是声明运行时 {@code /pages}。
 */
@RestController
public class OperatorContentPageEndpoint {

    /** JSON base for operator content pages — 操作员内容页 JSON 根路径. */
    public static final String PATH = JsonApi.BASE + "/content-pages";

    private final OperatorContentPageService pages;

    public OperatorContentPageEndpoint(OperatorContentPageService pages) {
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    @GetMapping(PATH)
    public List<ContentPageSummary> list(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return pages.list(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), status, limit);
    }

    @GetMapping(PATH + "/{pageId}")
    public ContentPageDocument get(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("pageId") String pageId) {
        return pages.get(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), pageId);
    }

    @PostMapping(PATH)
    public ContentPageDocument create(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody(required = false) SaveContentPageRequest body) {
        SaveContentPageRequest request = body == null ? new SaveContentPageRequest(null, null, null) : body;
        return pages.create(
                requireOperator(),
                resolveTenant(tenantHeader, tenantIdParam),
                request.slug(),
                request.title(),
                request.blocks());
    }

    @PutMapping(PATH + "/{pageId}")
    public ContentPageDocument update(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("pageId") String pageId,
            @RequestBody(required = false) SaveContentPageRequest body) {
        SaveContentPageRequest request = body == null ? new SaveContentPageRequest(null, null, null) : body;
        return pages.update(
                requireOperator(),
                resolveTenant(tenantHeader, tenantIdParam),
                pageId,
                request.slug(),
                request.title(),
                request.blocks());
    }

    @PostMapping(PATH + "/{pageId}/publish")
    public ContentPageDocument publish(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("pageId") String pageId) {
        return pages.publish(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), pageId);
    }

    @PostMapping(PATH + "/{pageId}/unpublish")
    public ContentPageDocument unpublish(
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @PathVariable("pageId") String pageId) {
        return pages.unpublish(requireOperator(), resolveTenant(tenantHeader, tenantIdParam), pageId);
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
