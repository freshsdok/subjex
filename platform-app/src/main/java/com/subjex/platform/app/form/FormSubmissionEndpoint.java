package com.subjex.platform.app.form;

import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.form.FormSubmissionStore.FormSubmissionRow;
import com.subjex.platform.app.security.DeclarationAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
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

/**
 * FormSubmissionEndpoint — 表单提交接口：按字段定义校验取值，落到对应的平台动作，执行声明副作用，并写入带声明版本的提交历史。
 * <p>
 * Each form's declared {@code permission} is checked (fail-closed). {@code tenantScoped} forms also need a tenant.
 * Domain actions come from the form's declared {@code domainAction} (catalog key), not from {@code formKey}
 * if-branches. Audit / task / extension side effects come from the checked-in {@code effects} list on the
 * form YAML, not hardcoded beside those actions.
 * Success returns submission id, declaration version, timestamps, and effect outcomes for the console debug panel.
 * Field violations return structured {@code fieldErrors}; missing declared permission is 403 with the permission name.
 * 每张表单按声明的 {@code permission} 检查（失败关闭）；{@code tenantScoped} 时还要租户。
 * 领域动作来自表单声明的 {@code domainAction}（目录键），不再按 formKey 分支。审计 / 任务 / 扩展副作用来自 YAML 的 {@code effects} 目录声明，不再写死在动作旁。
 * 成功响应含提交编号、声明版本、时间戳与副作用摘要，供控制台调试面板；字段违规返回结构化 fieldErrors；缺权限 403 带权限名。
 */
@RestController
public class FormSubmissionEndpoint {

    /** JSON path pattern — JSON 路径模式。 */
    public static final String PATH = JsonApi.BASE + "/forms/{formKey}/submissions";

    private static final int HISTORY_LIMIT = 50;

    private final FormCatalog forms;
    private final FormSubmissionStore submissions;
    private final FormDomainActionRunner domainActions;
    private final FormSideEffectRunner sideEffects;
    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public FormSubmissionEndpoint(
            FormCatalog forms,
            FormSubmissionStore submissions,
            FormDomainActionRunner domainActions,
            FormSideEffectRunner sideEffects,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess) {
        this.forms = forms;
        this.submissions = submissions;
        this.domainActions = domainActions;
        this.sideEffects = sideEffects;
        this.tenantGuard = tenantGuard;
        this.tenantAccess = tenantAccess;
    }

    @PostMapping(PATH)
    public FormSubmissionResultDocument submit(
            @PathVariable("formKey") String formKey,
            @RequestBody(required = false) FormSubmissionDocument document,
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantId) {
        RenderedForm form = forms.require(formKey);
        DeclarationAccess.requirePermission(operator, form.permission());
        DeclarationAccess.requireTenantWhenScoped(tenantGuard, tenantAccess, operator, form.tenantScoped(), tenantId);
        Map<String, Object> rawValues = document == null || document.values() == null
                ? Map.of()
                : document.values();
        Map<String, Object> accepted = validate(form, rawValues);
        String resultSummary = domainActions.apply(form, accepted);
        List<EffectOutcomeDocument> effectOutcomes = sideEffects.run(form, accepted, operator, tenantId);
        FormSubmissionRow row = submissions.save(
                formKey,
                form.version(),
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
        RenderedForm form = forms.require(formKey);
        DeclarationAccess.requirePermission(operator, form.permission());
        DeclarationAccess.requireTenantWhenScoped(tenantGuard, tenantAccess, operator, form.tenantScoped(), tenantId);
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
        if (field.kind() == FieldKind.TEXT) {
            String text = Objects.toString(raw, "").trim();
            if (field.maxLength() != null && text.length() > field.maxLength()) {
                throw new FormFieldCoerceException(
                        "too_long", "field " + field.name() + " is too long");
            }
            return text;
        }
        if (field.kind() == FieldKind.INTEGER) {
            int number = parseWholeNumber(field.name(), raw);
            if (field.minimum() != null && number < field.minimum()) {
                throw new FormFieldCoerceException(
                        "below_minimum", "field " + field.name() + " is below minimum");
            }
            if (field.maximum() != null && number > field.maximum()) {
                throw new FormFieldCoerceException(
                        "above_maximum", "field " + field.name() + " is above maximum");
            }
            return number;
        }
        throw new FormFieldCoerceException(
                "unsupported_kind", "field " + field.name() + " has an unsupported kind");
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
