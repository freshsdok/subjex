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
        assertEquals(5, catalog.list().size());
        assertTrue(catalog.knows("registry.register"));
        assertTrue(catalog.knows("config.override"));
        assertTrue(catalog.knows("entity.record.upsert"));
        assertTrue(catalog.knows("capability.algo.hashFingerprint"));
        assertTrue(catalog.knows("capability.ai.summarizePreview"));
        DomainActionSpec register = catalog.require("registry.register");
        assertEquals(DomainActionKey.REGISTRY_REGISTER, register.key());
        assertTrue(register.requiredFields().contains("serviceName"));
        assertTrue(register.requiredFields().contains("host"));
        assertTrue(register.requiredFields().contains("port"));
        DomainActionSpec override = catalog.require("config.override");
        assertEquals(DomainActionKey.CONFIG_OVERRIDE, override.key());
        assertTrue(override.requiredFields().contains("configKey"));
        assertTrue(override.requiredFields().contains("configValue"));
        DomainActionSpec upsert = catalog.require("entity.record.upsert");
        assertEquals(DomainActionKey.ENTITY_RECORD_UPSERT, upsert.key());
        assertTrue(upsert.requiredFields().isEmpty());
        assertTrue(upsert.optionalFields().isEmpty());
        DomainActionSpec hash = catalog.require("capability.algo.hashFingerprint");
        assertEquals(DomainActionKey.CAPABILITY_ALGO_HASH_FINGERPRINT, hash.key());
        assertTrue(hash.requiredFields().contains("inputText"));
        DomainActionSpec summarize = catalog.require("capability.ai.summarizePreview");
        assertEquals(DomainActionKey.CAPABILITY_AI_SUMMARIZE_PREVIEW, summarize.key());
        assertTrue(summarize.requiredFields().contains("inputText"));
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
