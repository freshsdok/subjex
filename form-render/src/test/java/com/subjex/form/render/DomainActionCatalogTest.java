package com.subjex.form.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * DomainActionCatalogTest — 领域动作目录测试：检入键齐全；未知键拒绝。
 */
class DomainActionCatalogTest {

    private final DomainActionCatalog catalog = new DomainActionCatalog();

    @Test
    void listsCheckedInActions() {
        assertEquals(3, catalog.list().size());
        assertTrue(catalog.knows("registry.register"));
        assertTrue(catalog.knows("config.override"));
        assertTrue(catalog.knows("entity.serviceNote.save"));
        DomainActionSpec register = catalog.require("registry.register");
        assertEquals(DomainActionKey.REGISTRY_REGISTER, register.key());
        assertTrue(register.requiredFields().contains("serviceName"));
        assertTrue(register.requiredFields().contains("host"));
        assertTrue(register.requiredFields().contains("port"));
        DomainActionSpec override = catalog.require("config.override");
        assertEquals(DomainActionKey.CONFIG_OVERRIDE, override.key());
        assertTrue(override.requiredFields().contains("configKey"));
        assertTrue(override.requiredFields().contains("configValue"));
        DomainActionSpec note = catalog.require("entity.serviceNote.save");
        assertEquals(DomainActionKey.ENTITY_SERVICE_NOTE_SAVE, note.key());
        assertTrue(note.requiredFields().contains("noteId"));
        assertTrue(note.requiredFields().contains("title"));
    }

    @Test
    void unknownKeyIsRejected() {
        assertThrows(FormDefinitionRejected.class, () -> catalog.require("webhook.call"));
    }

    @Test
    void incompleteCatalogYamlIsRejected() {
        String yaml = """
                actions:
                  - key: registry.register
                    titleEn: Register
                    titleZh: 登记
                    fields:
                      - name: serviceName
                        required: true
                """;
        assertThrows(FormDefinitionRejected.class, () -> new DomainActionCatalog(yaml));
    }
}
