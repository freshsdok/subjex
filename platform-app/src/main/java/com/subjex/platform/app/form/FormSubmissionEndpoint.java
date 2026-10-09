package com.subjex.platform.app.form;

import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.declaration.DeclarationKind;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.declaration.TenantDeclarationContext;
import com.subjex.platform.app.form.FormSubmissionStore.FormSubmissionRow;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.DeclarationAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.tenant.TenantQuotaService;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * FormSubmissionEndpoint — 表单提交接口：按字段定义校验取值，落到对应的平台动作，执行声明副作用，并写入带声明版本的提交历史。
 * <p>
 * Each form's declared {@code permission} is checked (fail-closed). {@code tenantScoped} forms also need a tenant.
 * Domain actions come from the form's declared {@code domainAction} (catalog key), not from {@code formKey}
 * if-branches. Audit / task side effects come from the checked-in {@code effects} list on the form YAML
 * (tenant drafts whitelist {@code audit.write}|{@code task.enqueue}; classpath may still use extension.invoke).
 * Success returns submission id, declaration version, timestamps, and effect outcomes for the console debug panel.
 * With non-blank {@code X-Tenant-Id}, resolves the form via {@link EffectiveDeclarationService} (DRAFT &gt; PROMOTED &gt; classpath), not classpath-only {@link FormCatalog#require}. Field violations return structured {@code fieldErrors}; missing declared permission is 403 with the permission name.
 * 每张表单按声明的 {@code permission} 检查（失败关闭）；{@code tenantScoped} 时还要租户。
 * 领域动作来自表单声明的 {@code domainAction}（目录键）。审计 / 任务副作用来自 YAML {@code effects}（租户草稿白名单；classpath 仍可 extension.invoke）。
 * 成功响应含提交编号、声明版本、时间戳与副作用摘要，供控制台调试面板；字段违规返回结构化 fieldErrors；缺权限 403 带权限名。
 */
@RestController
public class FormSubmissionEndpoint {

    /** JSON path pattern — JSON 路径模式。 */
    public static final String PATH = JsonApi.BASE + "/forms/{formKey}/submissions";

    private static final int HISTORY_LIMIT = 50;

    private final FormCatalog forms;
    private final EffectiveDeclarationService effective;
    private final FormSubmissionStore submissions;
    private final FormDomainActionRunner domainActions;
    private final FormSideEffectRunner sideEffects;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;
    private final TenantQuotaService tenantQuota;

    public FormSubmissionEndpoint(
            FormCatalog forms,
            EffectiveDeclarationService effective,
            FormSubmissionStore submissions,
            FormDomainActionRunner domainActions,
            FormSideEffectRunner sideEffects,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            TenantQuotaService tenantQuota) {
        this.forms = Objects.requireNonNull(forms, "forms");
        this.effective = Objects.requireNonNull(effective, "effective");
        this.submissions = submissions;
        this.domainActions = domainActions;
        this.sideEffects = sideEffects;
        this.tenantGuard = tenantGuard;
        this.tenantAccess = tenantAccess;
        this.tenantQuota = Objects.requireNonNull(tenantQuota, "tenantQuota");
    }

    @PostMapping(PATH)
    public FormSubmissionResultDocument submit(
            @PathVariable("formKey") String formKey,
            @RequestBody(required = false) FormSubmissionDocument document,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedForm form = resolveForm(formKey, tenantId);
        DeclarationAccess.require(
                operator,
                form.permission(),
                form.tenantScoped(),
                tenantId,
                tenantGuard,
                tenantAccess,
                AccessResource.of("form", formKey),
                AccessAction.of("submit"));
        requireGrantForNonScopedOverlay(operator, form, tenantId);
        String quotaTenant = (tenantId == null || tenantId.isBlank()) ? "platform" : tenantId.trim();
        tenantQuota.requireWithinQuota(operator, quotaTenant);
        Map<String, Object> rawValues = document == null || document.values() == null
                ? Map.of()
                : document.values();
        Map<String, Object> accepted = validate(form, rawValues);
        String resultSummary = domainActions.apply(form, accepted, tenantId, operator);
        String resolutionSource = resolveSource(formKey, tenantId);
        List<EffectOutcomeDocument> effectOutcomes =
                sideEffects.run(form, accepted, operator, tenantId, resolutionSource);
        FormSubmissionRow row = submissions.save(
                formKey,
                form.version(),
                quotaTenant,
                operator.identityId(),
                operator.getUsername(),
                accepted,
                resultSummary);
        return new FormSubmissionResultDocument(
                row.submissionId(),
                formKey,
                form.version(),
                resultSummary,
                row.submittedAt(),
                effectOutcomes);
    }

    @GetMapping(PATH)
    public FormSubmissionListDocument history(
            @PathVariable("formKey") String formKey,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedForm form = resolveForm(formKey, tenantId);
        DeclarationAccess.require(
                operator,
                form.permission(),
                form.tenantScoped(),
                tenantId,
                tenantGuard,
                tenantAccess,
                AccessResource.of("form", formKey),
                AccessAction.of("read"));
        requireGrantForNonScopedOverlay(operator, form, tenantId);
        List<FormSubmissionHistoryDocument> rows = submissions.listByFormKey(formKey, HISTORY_LIMIT).stream()
                .map(row -> new FormSubmissionHistoryDocument(
                        row.submissionId(),
                        row.formKey(),
                        row.declarationVersion(),
                        row.actorIdentityId(),
                        row.loginName(),
                        row.valuesJson(),
                        row.resultSummary(),
                        row.submittedAt()))
                .toList();
        return new FormSubmissionListDocument(rows);
    }

    private String resolveSource(String formKey, String tenantId) {
        if (TenantDeclarationContext.overlayRequested(tenantId)) {
            return effective.resolutionSource(tenantId.trim(), DeclarationKind.FORM, formKey);
        }
        return EffectiveDeclarationService.SOURCE_CLASSPATH;
    }

    private RenderedForm resolveForm(String formKey, String tenantId) {
        if (TenantDeclarationContext.overlayRequested(tenantId)) {
            return effective
                    .effectiveForm(tenantId.trim(), formKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return forms.require(formKey);
    }

    /**
     * After permission gate: non-scoped overlay still needs tenant grant —
     * 权限门禁之后：非租户隔离的覆盖仍要租户授权。
     */
    private void requireGrantForNonScopedOverlay(
            OperatorPrincipal operator, RenderedForm form, String tenantId) {
        if (TenantDeclarationContext.overlayRequested(tenantId) && !form.tenantScoped()) {
            tenantAccess.requireGranted(operator, tenantId.trim());
        }
    }

    /**
     * Checks required flags and field kinds against the rendered form definition.
     * Collects every field error so the console can show them together.
     * 对照已渲染的字段定义检查必填与类型；收集全部字段错误，供控制台一次展示。
     */
    static Map<String, Object> validate(RenderedForm form, Map<String, Object> rawValues) {
        Map<String, Object> accepted = new LinkedHashMap<>();
        List<FormFieldErrorDocument> errors = new ArrayList<>();
        for (FormField field : form.fields()) {
            Object raw = rawValues.get(field.name());
            if (raw == null || (raw instanceof String text && text.isBlank())) {
                if (field.required()) {
                    errors.add(new FormFieldErrorDocument(
                            field.name(), "required", "field " + field.name() + " is required"));
                }
                continue;
            }
            try {
                accepted.put(field.name(), coerce(field, raw));
            } catch (FormFieldCoerceException ex) {
                errors.add(new FormFieldErrorDocument(field.name(), ex.code(), ex.getMessage()));
            }
        }
        if (!errors.isEmpty()) {
            throw new FormValidationException(errors);
        }
        return accepted;
    }

    private static Object coerce(FormField field, Object raw) {
        return switch (field.kind()) {
            case TEXT, DATE, USER_REF -> coerceTextLike(field, raw);
            case ENUM -> {
                String text = coerceTextLike(field, raw);
                if (!field.enumValues().contains(text)) {
                    throw new FormFieldCoerceException(
                            "not_enum_value", "field " + field.name() + " must be one of enumValues");
                }
                yield text;
            }
            case INTEGER -> {
                int number = parseWholeNumber(field.name(), raw);
                if (field.minimum() != null && number < field.minimum()) {
                    throw new FormFieldCoerceException(
                            "below_minimum", "field " + field.name() + " is below minimum");
                }
                if (field.maximum() != null && number > field.maximum()) {
                    throw new FormFieldCoerceException(
                            "above_maximum", "field " + field.name() + " is above maximum");
                }
                yield number;
            }
            case BOOLEAN -> coerceBoolean(field.name(), raw);
        };
    }

    private static String coerceTextLike(FormField field, Object raw) {
        String text = Objects.toString(raw, "").trim();
        if (field.maxLength() != null && text.length() > field.maxLength()) {
            throw new FormFieldCoerceException(
                    "too_long", "field " + field.name() + " is too long");
        }
        return text;
    }

    private static Boolean coerceBoolean(String fieldName, Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        String text = Objects.toString(raw, "").trim();
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        throw new FormFieldCoerceException(
                "not_boolean", "field " + fieldName + " must be true or false");
    }

    private static int parseWholeNumber(String fieldName, Object raw) {
        if (raw instanceof Number number) {
            if (number.doubleValue() != number.intValue()) {
                throw new FormFieldCoerceException(
                        "not_integer", "field " + fieldName + " must be a whole number");
            }
            return number.intValue();
        }
        try {
            return Integer.parseInt(Objects.toString(raw, "").trim());
        } catch (NumberFormatException ex) {
            throw new FormFieldCoerceException(
                    "not_integer", "field " + fieldName + " must be a whole number");
        }
    }

    /**
     * Internal coerce failure carrying a machine-readable code — 内部强制转换失败，带机器可读 code。
     */
    private static final class FormFieldCoerceException extends RuntimeException {
        private final String code;

        FormFieldCoerceException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /**
     * FormSubmissionDocument — 提交请求体：字段名到取值的映射。
     */
    public record FormSubmissionDocument(Map<String, Object> values) {}

    /**
     * FormSubmissionResultDocument — 提交成功结果：编号、表单键、声明版本、摘要、时间与副作用摘要。
     */
    public record FormSubmissionResultDocument(
            String submissionId,
            String formKey,
            int declarationVersion,
            String resultSummary,
            Instant submittedAt,
            List<EffectOutcomeDocument> effects) {}

    /**
     * FormSubmissionListDocument — 提交历史列表。
     */
    public record FormSubmissionListDocument(List<FormSubmissionHistoryDocument> submissions) {}

    /**
     * FormSubmissionHistoryDocument — 历史中的一行。
     */
    public record FormSubmissionHistoryDocument(
            String submissionId,
            String formKey,
            int declarationVersion,
            String actorIdentityId,
            String loginName,
            String valuesJson,
            String resultSummary,
            Instant submittedAt) {}
}
