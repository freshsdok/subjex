package com.subjex.platform.app.form;

import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.form.FormSubmissionStore.FormSubmissionRow;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * FormSubmissionEndpoint — 表单提交接口：按字段定义校验取值，落到对应的平台动作，记审计，并写入提交历史。
 * <p>
 * {@code endpoint-publication} registers a service ({@code registry.write}, audit {@code registry.register}).
 * {@code config-override} writes a config override ({@code config.write}, audit {@code config.override}).
 * Unknown form keys and field violations are refused with 400. Missing write permission is 403.
 * History is listed with {@code page.read} at the security layer.
 * {@code endpoint-publication} 会登记服务（需要 {@code registry.write}，审计 {@code registry.register}）。
 * {@code config-override} 会写入配置覆盖（需要 {@code config.write}，审计 {@code config.override}）。
 * 未知表单键和字段违规以 400 拒绝。缺少写权限是 403。历史列表在安全层要求 {@code page.read}。
 */
@RestController
public class FormSubmissionEndpoint {

    /** JSON path pattern — JSON 路径模式。 */
    public static final String PATH = JsonApi.BASE + "/forms/{formKey}/submissions";

    private static final int HISTORY_LIMIT = 50;

    private static final String CONFIG_OVERRIDE_KEY = "config-override";

    private final FormCatalog forms;
    private final FormSubmissionStore submissions;
    private final ServiceCatalog services;
    private final ConfigCatalog config;
    private final OperatorActionAudit audit;

    public FormSubmissionEndpoint(
            FormCatalog forms,
            FormSubmissionStore submissions,
            ServiceCatalog services,
            ConfigCatalog config,
            OperatorActionAudit audit) {
        this.forms = forms;
        this.submissions = submissions;
        this.services = services;
        this.config = config;
        this.audit = audit;
    }

    @PostMapping(PATH)
    public FormSubmissionResultDocument submit(
            @PathVariable("formKey") String formKey,
            @RequestBody(required = false) FormSubmissionDocument document,
            @AuthenticationPrincipal OperatorPrincipal operator) {
        RenderedForm form = forms.require(formKey);
        Map<String, Object> rawValues = document == null || document.values() == null
                ? Map.of()
                : document.values();
        Map<String, Object> accepted = validate(form, rawValues);
        String resultSummary = applyAction(formKey, accepted, operator);
        FormSubmissionRow row = submissions.save(
                formKey, operator.identityId(), operator.getUsername(), accepted, resultSummary);
        return new FormSubmissionResultDocument(row.submissionId(), formKey, resultSummary);
    }

    @GetMapping(PATH)
    public FormSubmissionListDocument history(@PathVariable("formKey") String formKey) {
        forms.require(formKey);
        List<FormSubmissionHistoryDocument> rows = submissions.listByFormKey(formKey, HISTORY_LIMIT).stream()
                .map(row -> new FormSubmissionHistoryDocument(
                        row.submissionId(),
                        row.formKey(),
                        row.actorIdentityId(),
                        row.loginName(),
                        row.valuesJson(),
                        row.resultSummary(),
                        row.submittedAt()))
                .toList();
        return new FormSubmissionListDocument(rows);
    }

    private String applyAction(String formKey, Map<String, Object> accepted, OperatorPrincipal operator) {
        if (FormCatalog.PUBLICATION_KEY.equals(formKey)) {
            requirePermission(operator, OperatorPermission.REGISTRY_WRITE);
            String serviceName = stringValue(accepted, "serviceName");
            String host = stringValue(accepted, "host");
            int port = intValue(accepted, "port");
            services.register(new ServiceEndpoint(serviceName, host, port));
            audit.record(operator, OperatorActionAudit.REGISTRY_REGISTER, serviceName, AuditOutcome.ALLOWED);
            return serviceName + "@" + host + ":" + port;
        }
        if (CONFIG_OVERRIDE_KEY.equals(formKey)) {
            requirePermission(operator, OperatorPermission.CONFIG_WRITE);
            String configKey = stringValue(accepted, "configKey");
            String configValue = stringValue(accepted, "configValue");
            config.override(configKey, configValue);
            audit.record(operator, OperatorActionAudit.CONFIG_OVERRIDE, configKey, AuditOutcome.ALLOWED);
            return configKey + "=" + configValue;
        }
        throw new IllegalArgumentException("unknown form: " + formKey);
    }

    private static void requirePermission(OperatorPrincipal operator, OperatorPermission permission) {
        if (!operator.permissionNames().contains(permission.permissionName())) {
            throw new AccessDeniedException(permission.permissionName() + " required");
        }
    }

    /**
     * Checks required flags and field kinds against the rendered form definition.
     * 对照已渲染的字段定义检查必填与类型。
     */
    static Map<String, Object> validate(RenderedForm form, Map<String, Object> rawValues) {
        Map<String, Object> accepted = new LinkedHashMap<>();
        for (FormField field : form.fields()) {
            Object raw = rawValues.get(field.name());
            if (raw == null || (raw instanceof String text && text.isBlank())) {
                if (field.required()) {
                    throw new IllegalArgumentException("field " + field.name() + " is required");
                }
                continue;
            }
            accepted.put(field.name(), coerce(field, raw));
        }
        return accepted;
    }

    private static Object coerce(FormField field, Object raw) {
        if (field.kind() == FieldKind.TEXT) {
            String text = Objects.toString(raw, "").trim();
            if (field.maxLength() != null && text.length() > field.maxLength()) {
                throw new IllegalArgumentException("field " + field.name() + " is too long");
            }
            return text;
        }
        if (field.kind() == FieldKind.INTEGER) {
            int number = parseWholeNumber(field.name(), raw);
            if (field.minimum() != null && number < field.minimum()) {
                throw new IllegalArgumentException("field " + field.name() + " is below minimum");
            }
            if (field.maximum() != null && number > field.maximum()) {
                throw new IllegalArgumentException("field " + field.name() + " is above maximum");
            }
            return number;
        }
        throw new IllegalArgumentException("field " + field.name() + " has an unsupported kind");
    }

    private static int parseWholeNumber(String fieldName, Object raw) {
        if (raw instanceof Number number) {
            if (number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException("field " + fieldName + " must be a whole number");
            }
            return number.intValue();
        }
        try {
            return Integer.parseInt(Objects.toString(raw, "").trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("field " + fieldName + " must be a whole number");
        }
    }

    private static String stringValue(Map<String, Object> values, String name) {
        return Objects.toString(values.get(name), "");
    }

    private static int intValue(Map<String, Object> values, String name) {
        Object raw = values.get(name);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("field " + name + " must be a whole number");
    }

    /**
     * FormSubmissionDocument — 提交请求体：字段名到取值的映射。
     */
    public record FormSubmissionDocument(Map<String, Object> values) {}

    /**
     * FormSubmissionResultDocument — 提交结果：提交编号、表单键、结果摘要。
     */
    public record FormSubmissionResultDocument(String submissionId, String formKey, String resultSummary) {}

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
            String actorIdentityId,
            String loginName,
            String valuesJson,
            String resultSummary,
            Instant submittedAt) {}
}
