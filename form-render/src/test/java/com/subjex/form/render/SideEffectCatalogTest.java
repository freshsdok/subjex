package com.subjex.form.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * SideEffectCatalogTest — 副作用目录测试：检入 YAML 列出三项已知键；缺键或未知键拒绝。
 */
class SideEffectCatalogTest {

    @Test
    void loadsTheCheckedInCatalog() {
        SideEffectCatalog catalog = new SideEffectCatalog();
        assertEquals(3, catalog.list().size());
        assertTrue(catalog.knows("audit.write"));
        assertTrue(catalog.knows("task.enqueue"));
        assertTrue(catalog.knows("extension.invoke"));
        assertEquals(SideEffectKey.AUDIT_WRITE, catalog.require("audit.write").key());
        assertTrue(catalog.require("audit.write").requiredParams().contains("actionName"));
    }

    @Test
    void unknownKeyIsRejected() {
        SideEffectCatalog catalog = new SideEffectCatalog();
        assertThrows(FormDefinitionRejected.class, () -> catalog.require("webhook.call"));
    }

    @Test
    void catalogMissingKnownKeyIsRejected() {
        String yaml = """
                effects:
                  - key: audit.write
                    titleEn: Write audit entry
                    titleZh: 写入审计
                    params:
                      - name: actionName
                        required: true
                """;
        assertThrows(FormDefinitionRejected.class, () -> new SideEffectCatalog(yaml));
    }
}
