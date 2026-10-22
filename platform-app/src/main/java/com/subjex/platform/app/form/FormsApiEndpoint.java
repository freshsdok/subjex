package com.subjex.platform.app.form;

import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.declaration.DeclarationKind;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.declaration.TenantDeclarationContext;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.DeclarationAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.TenantEnforcementFilter;
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
 * FormsApiEndpoint — 表单接口：目录索引列出每一份表单的键、标题、版本、权限、租户标志与领域动作；详情给出字段。
 * <p>
 * Index needs {@code page.read} at the security layer and exposes declaration flags for the console.
 * With non-blank {@code X-Tenant-Id} (and tenant grant), index merges classpath with effective tenant
 * declarations (DRAFT/PROMOTED keys). Detail uses the same overlay; otherwise classpath {@link FormCatalog}.
 * 目录在安全层要 {@code page.read}；带租户头时合并租户生效声明。详情同样覆盖，否则 classpath。
 * Detail AuthZ via {@link DeclarationAccess#requireVia} / {@link PolicyEngine} (AuthZ-1f).
 * 详情鉴权经 PolicyEngine（AuthZ-1f）。
 */
@RestController
public class FormsApiEndpoint {

    /** JSON path — JSON 路径。 */
    public static final String PATH = JsonApi.BASE + "/forms";

    private final FormCatalog catalog;
    private final EffectiveDeclarationService effective;
    private final OperatorTenantAccess tenantAccess;
    private final PolicyEngine policyEngine;

    public FormsApiEndpoint(
            FormCatalog catalog,
            EffectiveDeclarationService effective,
            OperatorTenantAccess tenantAccess,
            PolicyEngine policyEngine) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.effective = Objects.requireNonNull(effective, "effective");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
    }

    @GetMapping(PATH)
    public FormsIndexDocument index(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        Map<String, FormIndexDocument> byKey = new LinkedHashMap<>();
        for (RenderedForm form : catalog.list()) {
            byKey.put(form.formKey(), toIndex(form));
        }
        if (TenantDeclarationContext.overlayRequested(tenantId)) {
            String tid = tenantId.trim();
            tenantAccess.requireGranted(operator, tid);
            for (String key : effective.tenantDeclarationKeys(tid, DeclarationKind.FORM)) {
                effective.effectiveForm(tid, key).ifPresent(form -> byKey.put(form.formKey(), toIndex(form)));
            }
        }
        return new FormsIndexDocument(List.copyOf(byKey.values()));
    }

    private static FormIndexDocument toIndex(RenderedForm form) {
        return new FormIndexDocument(
                form.formKey(),
                form.titleZh(),
                form.titleEn(),
                form.version(),
                form.permission(),
                form.tenantScoped(),
                form.domainAction().key(),
                form.entityKey());
    }

    @GetMapping(PATH + "/{formKey}")
    public FormsDocument detail(
            @PathVariable("formKey") String formKey,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedForm form = resolveForm(formKey, tenantId);
        // AuthZ-1f: PolicyEngine (Cedar default) — 经 PolicyEngine，默认 Cedar。
        DeclarationAccess.requireVia(
                policyEngine,
                operator,
                form.permission(),
                form.tenantScoped(),
                tenantId,
                AccessResource.of("form", formKey),
                AccessAction.of("read"));
        if (TenantDeclarationContext.overlayRequested(tenantId) && !form.tenantScoped()) {
            tenantAccess.requireGranted(operator, tenantId.trim());
        }
        List<FieldDocument> fields = form.fields().stream()
                .map(field -> new FieldDocument(
                        field.name(),
                        field.kind().wireName(),
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

    private RenderedForm resolveForm(String formKey, String tenantId) {
        if (TenantDeclarationContext.overlayRequested(tenantId)) {
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
