package com.subjex.platform.app.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.subjex.form.render.RenderedForm;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FormSubmissionValidationTest — 表单提交校验测试：必填、长度和整数范围按 YAML 定义拒绝。
 */
class FormSubmissionValidationTest {

    private final RenderedForm form = new FormCatalog().require("endpoint-publication");

    @Test
    void acceptsAValidEndpointPublication() {
        Map<String, Object> accepted = FormSubmissionEndpoint.validate(
                form, Map.of("serviceName", "billing", "host", "10.0.0.8", "port", 8080));
        assertEquals("billing", accepted.get("serviceName"));
        assertEquals("10.0.0.8", accepted.get("host"));
        assertEquals(8080, accepted.get("port"));
    }

    @Test
    void refusesMissingRequiredField() {
        assertThrows(IllegalArgumentException.class, () -> FormSubmissionEndpoint.validate(
                form, Map.of("serviceName", "billing", "host", "10.0.0.8")));
    }

    @Test
    void refusesPortOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> FormSubmissionEndpoint.validate(
                form, Map.of("serviceName", "billing", "host", "10.0.0.8", "port", 70000)));
    }
}
