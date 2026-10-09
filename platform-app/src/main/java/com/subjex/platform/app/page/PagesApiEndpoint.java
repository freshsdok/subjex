package com.subjex.platform.app.page;

import com.subjex.page.declare.RenderedFlow;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.declaration.DeclarationKind;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.declaration.TenantDeclarationContext;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.DeclarationAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * PagesApiEndpoint — 页面接口：目录索引列出每一份流程的键、标题、版本、权限与租户标志；详情给出三页描述。
 * <p>
 * Index needs {@code page.read}. With non-blank {@code X-Tenant-Id} (and grant), merges classpath
 * with effective tenant flows (DRAFT &gt; PROMOTED &gt; classpath). Detail uses the same overlay;
 * else classpath. RT-5: no separate page YAML — promoting a FLOW only ensures fixed
 * {@code /pages/{key}} paths (see {@link com.subjex.platform.app.declaration.DeclarationRuntimePages});
 * catalog entries appear from the promoted (or draft) flow at read time.
 * 目录要 {@code page.read}；带租户头合并租户生效流程（草稿 &gt; 已晋升 &gt; classpath）。
 * RT-5：不另写 page YAML；晋升 FLOW 只校验固定路径，目录在读时从生效 flow 出现。
 */
@RestController
public class PagesApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/pages";

    private final PageCatalog catalog;
    private final EffectiveDeclarationService effective;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public PagesApiEndpoint(
            PageCatalog catalog,
            EffectiveDeclarationService effective,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.effective = Objects.requireNonNull(effective, "effective");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
    }

    @GetMapping(PATH)
    public PagesIndexDocument index(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        Map<String, PageIndexDocument> byKey = new LinkedHashMap<>();
        for (RenderedFlow flow : catalog.list()) {
            byKey.put(flow.flowKey(), toIndex(flow));
        }
        if (TenantDeclarationContext.overlayRequested(tenantId)) {
            String tid = tenantId.trim();
            tenantAccess.requireGranted(operator, tid);
            for (String key : effective.tenantDeclarationKeys(tid, DeclarationKind.FLOW)) {
                effective.effectiveFlow(tid, key).ifPresent(flow -> byKey.put(flow.flowKey(), toIndex(flow)));
            }
        }
        return new PagesIndexDocument(List.copyOf(byKey.values()));
    }

    private static PageIndexDocument toIndex(RenderedFlow flow) {
        return new PageIndexDocument(
                flow.flowKey(),
                flow.titleZh(),
                flow.titleEn(),
                flow.formKey(),
                flow.entityKey(),
                flow.version(),
                flow.permission(),
                flow.tenantScoped());
    }

    @GetMapping(PATH + "/{flowKey}")
    public PageFlowDocument detail(
            @PathVariable("flowKey") String flowKey,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedFlow flow = resolveFlow(flowKey, tenantId);
        DeclarationAccess.require(
                operator,
                flow.permission(),
                flow.tenantScoped(),
                tenantId,
                tenantGuard,
                tenantAccess,
                AccessResource.of("flow", flowKey),
                AccessAction.of("read"));
        if (TenantDeclarationContext.overlayRequested(tenantId) && !flow.tenantScoped()) {
            tenantAccess.requireGranted(operator, tenantId.trim());
        }
        return new PageFlowDocument(
                flow.flowKey(),
                flow.titleZh(),
                flow.titleEn(),
                flow.formKey(),
                flow.entityKey(),
                flow.version(),
                flow.permission(),
                flow.tenantScoped(),
                new ListSpecDocument(
                        flow.list().path(), flow.list().apiPath(), flow.list().itemsKey(), flow.list().blocks()),
                new DetailSpecDocument(
                        flow.detail().path(),
                        flow.detail().apiPath(),
                        flow.detail().itemsKey(),
                        flow.detail().idField(),
                        flow.detail().blocks()),
                new SubmitSpecDocument(
                        flow.submit().path(),
                        flow.submit().apiPath(),
                        flow.submit().redirectTo(),
                        flow.submit().blocks(),
                        flow.submit().capabilityId()));
    }

    private RenderedFlow resolveFlow(String flowKey, String tenantId) {
        if (TenantDeclarationContext.overlayRequested(tenantId)) {
            return effective
                    .effectiveFlow(tenantId.trim(), flowKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return catalog.require(flowKey);
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
    public record ListSpecDocument(String path, String apiPath, String itemsKey, List<String> blocks) {}

    /**
     * DetailSpecDocument — 详情页描述。
     */
    public record DetailSpecDocument(String path, String apiPath, String itemsKey, String idField, List<String> blocks) {}

    /**
     * SubmitSpecDocument — 提交页描述。
     */
    public record SubmitSpecDocument(String path, String apiPath, String redirectTo, List<String> blocks, String capabilityId) {}
}
