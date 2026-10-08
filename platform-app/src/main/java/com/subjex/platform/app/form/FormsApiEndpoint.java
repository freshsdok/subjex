package com.subjex.platform.app.form;

import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.declaration.TenantDeclarationContext;
import com.subjex.platform.app.security.DeclarationAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * FormsApiEndpoint — 表单接口：目录索引列出每一份表单的键、标题、版本、权限、租户标志与领域动作；详情给出字段。
 * <p>
 * Index needs {@code page.read} at the security layer and exposes declaration flags for the console.
 * Detail checks the form's declared permission (fail-closed) and tenant when {@code tenantScoped}.
 * With non-blank {@code X-Tenant-Id}, detail uses DB draft overlay via {@link EffectiveDeclarationService}
 * when present; otherwise classpath {@link FormCatalog}.
 * 目录在安全层要 {@code page.read}，并暴露声明上的权限/租户标志供控制台显隐。
 * 详情按表单声明的权限检查（失败关闭）；{@code tenantScoped} 时再过租户门禁。
 * 带租户头时详情优先库内草稿覆盖，否则 classpath。
 */
@RestController
public class FormsApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/forms";

    private final FormCatalog catalog;
    private final EffectiveDeclarationService effective;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public FormsApiEndpoint(
            FormCatalog catalog,
            EffectiveDeclarationService effective,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.effective = Objects.requireNonNull(effective, "effective");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
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
                        form.tenantScoped(),
                        form.domainAction().key(),
                        form.entityKey()))
                .toList();
        return new FormsIndexDocument(forms);
    }

    @GetMapping(PATH + "/{formKey}")
    public FormsDocument detail(
            @PathVariable("formKey") String formKey,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedForm form = resolveForm(formKey, operator, tenantId);
        DeclarationAccess.requirePermission(operator, form.permission());
        DeclarationAccess.requireTenantWhenScoped(tenantGuard, tenantAccess, operator, form.tenantScoped(), tenantId);
        List<FieldDocument> fields = form.fields().stream()
                .map(field -> new FieldDocument(
                        field.name(),
                        field.kind().name().toLowerCase(Locale.ROOT),
                        field.required(),
                        field.enumValues()))
                .toList();
        return new FormsDocument(
                form.formKey(),
                form.titleZh(),
                form.titleEn(),
                form.version(),
                form.permission(),
                form.tenantScoped(),
                form.domainAction().key(),
                form.entityKey(),
                fields);
    }

    private RenderedForm resolveForm(String formKey, OperatorPrincipal operator, String tenantId) {
        if (TenantDeclarationContext.overlayRequested(tenantId)) {
            tenantAccess.requireGranted(operator, tenantId.trim());
            return effective
                    .effectiveForm(tenantId.trim(), formKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return catalog.require(formKey);
    }

    /**
     * FormsIndexDocument — 表单目录：每一份表单的键、中英标题、版本、权限与是否租户隔离。
     */
    public record FormsIndexDocument(List<FormIndexDocument> forms) {}

    /**
     * FormIndexDocument — 目录中的一项：表单键、中文标题、英文标题、声明版本、权限、是否租户隔离、领域动作键、可选实体键。
     */
    public record FormIndexDocument(
            String formKey,
            String titleZh,
            String titleEn,
            int version,
            String permission,
            boolean tenantScoped,
            String domainAction,
            String entityKey) {}

    /**
     * FormsDocument — 表单详情：表单键、中文标题、英文标题、声明版本、权限、是否租户隔离、领域动作键、可选实体键，以及字段。
     */
    public record FormsDocument(
            String formKey,
            String titleZh,
            String titleEn,
            int version,
            String permission,
            boolean tenantScoped,
            String domainAction,
            String entityKey,
            List<FieldDocument> fields) {}

    /**
     * FieldDocument — 一个字段：名字、类型词（字段种类的小写名）、是否必填、枚举可选值（非 enum 为空列表）。
     */
    public record FieldDocument(String name, String kind, boolean required, List<String> enumValues) {}
}
