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
        assertEquals(4, catalog.list().size());
        assertTrue(catalog.knows("algo.hashFingerprint"));
        assertTrue(catalog.knows("algo.normalizeWhitespace"));
        assertTrue(catalog.knows("ai.summarizePreview"));
        assertTrue(catalog.knows("ai.suggestTitlePreview"));
        CapabilitySpec algo = catalog.require("algo.hashFingerprint");
        assertEquals(CapabilityKind.ALGORITHM, algo.kind());
        assertEquals("Hash fingerprint", algo.titleEn());
        CapabilitySpec normalize = catalog.require("algo.normalizeWhitespace");
        assertEquals(CapabilityKind.ALGORITHM, normalize.kind());
        assertEquals("Normalize whitespace", normalize.titleEn());
        CapabilitySpec ai = catalog.require("ai.summarizePreview");
        assertEquals(CapabilityKind.AI, ai.kind());
        assertEquals("Summarize preview", ai.titleEn());
        CapabilitySpec title = catalog.require("ai.suggestTitlePreview");
        assertEquals(CapabilityKind.AI, title.kind());
        assertEquals("Suggest title preview", title.titleEn());
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
                  - id: algo.normalizeWhitespace
                    titleEn: Norm
                    titleZh: 空
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
                  - id: ai.suggestTitlePreview
                    titleEn: Title
                    titleZh: 标
                    summaryEn: s
                    summaryZh: 摘
                """;
        // Drop algo entry entirely / put wrong kind in algo file
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
        assertEquals(4, new CapabilityCatalog(algo, ai).list().size());
    }
}
