package com.subjex.platform.app.form;

import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * FormSubmissionEndpoint — 表单提交接口：按字段定义校验取值，然后落到对应的平台动作并记审计。
 * <p>
 * Today only {@code endpoint-publication} is accepted; a valid body becomes a service registration
 * ({@code registry.write}, audit {@code registry.register}). Unknown form keys and field violations
 * are refused with 400.
 * 目前只接受 {@code endpoint-publication}；合法请求体会变成一次服务登记（需要 {@code registry.write}，
 * 审计 {@code registry.register}）。未知表单键和字段违规以 400 拒绝。
 */
@RestController
public class FormSubmissionEndpoint {

    /** JSON path pattern — JSON 路径模式。 */
    public static final String PATH = JsonApi.BASE + "/forms/{formKey}/submissions";

    private final FormCatalog forms;
    private final ServiceCatalog services;
    private final OperatorActionAudit audit;

    public FormSubmissionEndpoint(FormCatalog forms, ServiceCatalog services, OperatorActionAudit audit) {
        this.forms = forms;
        this.services = services;
        this.audit = audit;
    }

    @PostMapping(PATH)
    public FormSubmissionResultDocument submit(
            @PathVariable("formKey") String formKey,
            @RequestBody(required = false) FormSubmissionDocument document,
            @AuthenticationPrincipal OperatorPrincipal operator) {
        RenderedForm form = forms.publication();
        if (!form.formKey().equals(formKey)) {
            throw new IllegalArgumentException("unknown form: " + formKey);
        }
        Map<String, Object> rawValues = document == null || document.values() == null
                ? Map.of()
                : document.values();
        Map<String, Object> accepted = validate(form, rawValues);
        String serviceName = stringValue(accepted, "serviceName");
        String host = stringValue(accepted, "host");
        int port = intValue(accepted, "port");
        services.register(new ServiceEndpoint(serviceName, host, port));
        audit.record(operator, OperatorActionAudit.REGISTRY_REGISTER, serviceName, AuditOutcome.ALLOWED);
        return new FormSubmissionResultDocument(formKey, serviceName, host, port);
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
     * FormSubmissionResultDocument — 提交结果：表单键与落到登记的服务名、主机、端口。
     */
    public record FormSubmissionResultDocument(String formKey, String serviceName, String host, int port) {}
}
