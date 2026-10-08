package com.subjex.platform.app.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * CapabilityCatalogTest — 能力目录：检入键齐全；未知键拒绝；缺项 YAML 拒绝。
 */
class CapabilityCatalogTest {

    private final CapabilityCatalog catalog = new CapabilityCatalog();

    @Test
    void listsCheckedInCapabilities() {
        assertEquals(2, catalog.list().size());
        assertTrue(catalog.knows("algo.hashFingerprint"));
        assertTrue(catalog.knows("ai.summarizePreview"));
        CapabilitySpec algo = catalog.require("algo.hashFingerprint");
        assertEquals(CapabilityKind.ALGORITHM, algo.kind());
        assertEquals("Hash fingerprint", algo.titleEn());
        CapabilitySpec ai = catalog.require("ai.summarizePreview");
        assertEquals(CapabilityKind.AI, ai.kind());
        assertEquals("Summarize preview", ai.titleEn());
    }

    @Test
    void unknownKeyIsRejected() {
        assertThrows(CapabilityRejected.class, () -> catalog.require("algo.unknown"));
    }

    @Test
    void incompleteAlgorithmYamlIsRejected() {
        String algo = """
                capabilities:
                  - id: algo.hashFingerprint
                    titleEn: Hash
                    titleZh: 哈希
                    summaryEn: s
                    summaryZh: 摘
                """;
        String ai = """
                capabilities:
                  - id: ai.summarizePreview
                    titleEn: Sum
                    titleZh: 摘
                    summaryEn: s
                    summaryZh: 摘
                """;
        // Drop algo entry entirely
        String emptyAlgo = """
                capabilities:
                  - id: ai.summarizePreview
                    titleEn: wrong kind
                    titleZh: 错
                    summaryEn: s
                    summaryZh: 摘
                """;
        assertThrows(CapabilityRejected.class, () -> new CapabilityCatalog(emptyAlgo, ai));
        assertThrows(
                CapabilityRejected.class,
                () -> new CapabilityCatalog(
                        """
                        capabilities: []
                        """,
                        ai));
        // Sanity: valid pair loads
        assertEquals(2, new CapabilityCatalog(algo, ai).list().size());
    }
}
