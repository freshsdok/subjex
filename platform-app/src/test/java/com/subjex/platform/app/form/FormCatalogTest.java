package com.subjex.platform.app.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.form.render.RenderedForm;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FormCatalogTest — 表单目录测试：classpath 上的每一份 YAML 都能按 formKey 找到。
 */
class FormCatalogTest {

    private final FormCatalog catalog = new FormCatalog();

    @Test
    void listsAndRequiresEndpointPublication() {
        List<RenderedForm> forms = catalog.list();
        assertTrue(forms.stream().anyMatch(form -> "endpoint-publication".equals(form.formKey())));
        assertTrue(forms.stream().anyMatch(form -> "config-override".equals(form.formKey())));
        assertEquals("endpoint-publication", catalog.publication().formKey());
        assertEquals("endpoint-publication", catalog.require("endpoint-publication").formKey());
        assertEquals("config-override", catalog.require("config-override").formKey());
        assertTrue(catalog.find("endpoint-publication").isPresent());
        assertThrows(IllegalArgumentException.class, () -> catalog.require("no-such-form"));
    }
}
