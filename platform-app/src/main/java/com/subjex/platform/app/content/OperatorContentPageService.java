package com.subjex.platform.app.content;

import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.app.security.PolicyContext;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyPrincipal;
import com.subjex.platform.app.security.PolicyResource;
import com.subjex.platform.contract.content.ContentBlock;
import com.subjex.platform.contract.content.ContentPageDocument;
import com.subjex.platform.contract.content.ContentPagePort;
import com.subjex.platform.contract.content.ContentPageStatus;
import com.subjex.platform.contract.content.ContentPageSummary;
import com.subjex.platform.contract.content.SaveContentPageCommand;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;

/**
 * OperatorContentPageService — create, edit, publish, and unpublish pages in a granted tenant.
 * 操作员在已授权租户内创建、编辑、发布或取消发布内容页。
 */
public final class OperatorContentPageService {

    private final ContentPagePort pages;
    private final OperatorTenantAccess tenantAccess;
    private final PolicyEngine policyEngine;

    public OperatorContentPageService(
            ContentPagePort pages, OperatorTenantAccess tenantAccess, PolicyEngine policyEngine) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
    }

    public List<ContentPageSummary> list(
            OperatorPrincipal operator, String tenantId, String status, Integer limit) {
        String tid = requireOperatorAccess(operator, tenantId, OperatorPermission.CONTENT_READ, "read");
        return pages.list(tid, parseStatus(status), ContentPageRules.clampLimit(limit));
    }

    public ContentPageDocument get(OperatorPrincipal operator, String tenantId, String pageId) {
        String tid = requireOperatorAccess(operator, tenantId, OperatorPermission.CONTENT_READ, "read");
        return pages.get(tid, pageId);
    }

    public ContentPageDocument create(
            OperatorPrincipal operator,
            String tenantId,
            String slug,
            String title,
            List<ContentBlock> blocks) {
        String tid = requireOperatorAccess(operator, tenantId, OperatorPermission.CONTENT_WRITE, "write");
        return pages.create(new SaveContentPageCommand(tid, null, slug, title, blocks, operator.subjectId()));
    }

    public ContentPageDocument update(
            OperatorPrincipal operator,
            String tenantId,
            String pageId,
            String slug,
            String title,
            List<ContentBlock> blocks) {
        String tid = requireOperatorAccess(operator, tenantId, OperatorPermission.CONTENT_WRITE, "write");
        return pages.update(new SaveContentPageCommand(tid, pageId, slug, title, blocks, operator.subjectId()));
    }

    public ContentPageDocument publish(OperatorPrincipal operator, String tenantId, String pageId) {
        String tid = requireOperatorAccess(operator, tenantId, OperatorPermission.CONTENT_WRITE, "write");
        return pages.publish(tid, pageId, operator.subjectId());
    }

    public ContentPageDocument unpublish(OperatorPrincipal operator, String tenantId, String pageId) {
        String tid = requireOperatorAccess(operator, tenantId, OperatorPermission.CONTENT_WRITE, "write");
        return pages.unpublish(tid, pageId, operator.subjectId());
    }

    private String requireOperatorAccess(
            OperatorPrincipal operator, String tenantId, OperatorPermission permission, String action) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String tid = tenantId.trim();
        if (JdbcContentPagePort.RESERVED_TENANT.equals(tid)) {
            throw new AccessDeniedException("reserved-tenant");
        }
        tenantAccess.requireGranted(operator, tid);
        policyEngine.require(
                PolicyPrincipal.from(operator),
                permission.permissionName(),
                AccessAction.of(action),
                PolicyResource.of("content", "_").withAttribute(PolicyResource.ATTR_TENANT_ID, tid),
                PolicyContext.of(tid, false, OrganizationScope.unrestricted()));
        return tid;
    }

    private static ContentPageStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ContentPageStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown content page status: " + raw.trim());
        }
    }
}
