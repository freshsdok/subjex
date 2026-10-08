package com.subjex.platform.app.page;

import com.subjex.page.declare.RenderedFlow;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.DeclarationAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * PagesApiEndpoint — 页面接口：目录索引列出每一份流程的键、标题、版本、权限与租户标志；详情给出三页描述。
 * <p>
 * Index needs {@code page.read} at the security layer and exposes declaration flags for the console.
 * Detail checks the flow's declared permission (fail-closed) and tenant when {@code tenantScoped}.
 * 目录在安全层要 {@code page.read}，并暴露声明上的权限/租户标志供控制台显隐。
 * 详情按流程声明的权限检查（失败关闭）；{@code tenantScoped} 时再过租户门禁。
 */
@RestController
public class PagesApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/pages";

    private final PageCatalog catalog;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public PagesApiEndpoint(PageCatalog catalog, TenantGuard tenantGuard, OperatorTenantAccess tenantAccess) {
        this.catalog = catalog;
        this.tenantGuard = tenantGuard;
        this.tenantAccess = tenantAccess;
    }

    @GetMapping(PATH)
    public PagesIndexDocument index() {
        List<PageIndexDocument> pages = catalog.list().stream()
                .map(flow -> new PageIndexDocument(
                        flow.flowKey(),
                        flow.titleZh(),
                        flow.titleEn(),
                        flow.formKey(),
                        flow.entityKey(),
                        flow.version(),
                        flow.permission(),
                        flow.tenantScoped()))
                .toList();
        return new PagesIndexDocument(pages);
    }

    @GetMapping(PATH + "/{flowKey}")
    public PageFlowDocument detail(
            @PathVariable("flowKey") String flowKey,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedFlow flow = catalog.require(flowKey);
        DeclarationAccess.requirePermission(operator, flow.permission());
        DeclarationAccess.requireTenantWhenScoped(tenantGuard, tenantAccess, operator, flow.tenantScoped(), tenantId);
        return new PageFlowDocument(
                flow.flowKey(),
                flow.titleZh(),
                flow.titleEn(),
                flow.formKey(),
                flow.entityKey(),
                flow.version(),
                flow.permission(),
                flow.tenantScoped(),
                new ListSpecDocument(flow.list().path(), flow.list().apiPath(), flow.list().itemsKey()),
                new DetailSpecDocument(
                        flow.detail().path(),
                        flow.detail().apiPath(),
                        flow.detail().itemsKey(),
                        flow.detail().idField()),
                new SubmitSpecDocument(
                        flow.submit().path(), flow.submit().apiPath(), flow.submit().redirectTo()));
    }

    /**
     * PagesIndexDocument — 流程目录：每一份流程的键、中英标题、表单/实体键、版本、权限与是否租户隔离。
     */
    public record PagesIndexDocument(List<PageIndexDocument> pages) {}

    /**
     * PageIndexDocument — 目录中的一项：流程键、中英标题、表单/实体键、声明版本、权限、是否租户隔离。
     */
    public record PageIndexDocument(
            String flowKey,
            String titleZh,
            String titleEn,
            String formKey,
            String entityKey,
            int version,
            String permission,
            boolean tenantScoped) {}

    /**
     * PageFlowDocument — 流程详情：三页路径与 API、表单/实体键、声明版本，以及权限与租户标志。
     */
    public record PageFlowDocument(
            String flowKey,
            String titleZh,
            String titleEn,
            String formKey,
            String entityKey,
            int version,
            String permission,
            boolean tenantScoped,
            ListSpecDocument list,
            DetailSpecDocument detail,
            SubmitSpecDocument submit) {}

    /**
     * ListSpecDocument — 列表页描述。
     */
    public record ListSpecDocument(String path, String apiPath, String itemsKey) {}

    /**
     * DetailSpecDocument — 详情页描述。
     */
    public record DetailSpecDocument(String path, String apiPath, String itemsKey, String idField) {}

    /**
     * SubmitSpecDocument — 提交页描述。
     */
    public record SubmitSpecDocument(String path, String apiPath, String redirectTo) {}
}
