package com.subjex.platform.app.form;

import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.DeclarationAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * FormsApiEndpoint — 表单接口：目录索引列出每一份表单的键、标题、版本、权限与租户标志；详情给出字段。
 * <p>
 * Index needs {@code page.read} at the security layer and exposes declaration flags for the console.
 * Detail checks the form's declared permission (fail-closed) and tenant when {@code tenantScoped}.
 * 目录在安全层要 {@code page.read}，并暴露声明上的权限/租户标志供控制台显隐。
 * 详情按表单声明的权限检查（失败关闭）；{@code tenantScoped} 时再过租户门禁。
 */
@RestController
public class FormsApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/forms";

    private final FormCatalog catalog;
    private final TenantGuard tenantGuard;

    public FormsApiEndpoint(FormCatalog catalog, TenantGuard tenantGuard) {
        this.catalog = catalog;
        this.tenantGuard = tenantGuard;
    }

    @GetMapping(PATH)
    public FormsIndexDocument index() {
        List<FormIndexDocument> forms = catalog.list().stream()
                .map(form -> new FormIndexDocument(
                        form.formKey(),
                        form.titleZh(),
                        form.titleEn(),
                        form.version(),
                        form.permission(),
                        form.tenantScoped()))
                .toList();
        return new FormsIndexDocument(forms);
    }

    @GetMapping(PATH + "/{formKey}")
    public FormsDocument detail(
            @PathVariable("formKey") String formKey,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedForm form = catalog.require(formKey);
        DeclarationAccess.requirePermission(operator, form.permission());
        DeclarationAccess.requireTenantWhenScoped(tenantGuard, form.tenantScoped(), tenantId);
        List<FieldDocument> fields = form.fields().stream()
                .map(field -> new FieldDocument(
                        field.name(),
                        field.kind().name().toLowerCase(Locale.ROOT),
                        field.required()))
                .toList();
        return new FormsDocument(
                form.formKey(),
                form.titleZh(),
                form.titleEn(),
                form.version(),
                form.permission(),
                form.tenantScoped(),
                fields);
    }

    /**
     * FormsIndexDocument — 表单目录：每一份表单的键、中英标题、版本、权限与是否租户隔离。
     */
    public record FormsIndexDocument(List<FormIndexDocument> forms) {}

    /**
     * FormIndexDocument — 目录中的一项：表单键、中文标题、英文标题、声明版本、权限、是否租户隔离。
     */
    public record FormIndexDocument(
            String formKey,
            String titleZh,
            String titleEn,
            int version,
            String permission,
            boolean tenantScoped) {}

    /**
     * FormsDocument — 表单详情：表单键、中文标题、英文标题、声明版本、权限、是否租户隔离，以及字段。
     */
    public record FormsDocument(
            String formKey,
            String titleZh,
            String titleEn,
            int version,
            String permission,
            boolean tenantScoped,
            List<FieldDocument> fields) {}

    /**
     * FieldDocument — 一个字段：名字、类型词（字段种类的小写名）、是否必填。
     */
    public record FieldDocument(String name, String kind, boolean required) {}
}
