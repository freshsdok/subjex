package com.subjex.platform.app.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CapabilityRunnerTest — 能力桩：哈希确定性；AI 预览固定模板且不依赖外部。
 */
class CapabilityRunnerTest {

    private final CapabilityRunner runner = new CapabilityRunner(new CapabilityCatalog());

    @Test
    void hashFingerprintIsDeterministicSha256() throws Exception {
        String input = "hello-subjex";
        String expected = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, runner.run("algo.hashFingerprint", Map.of("inputText", input)));
        assertEquals(expected, runner.run("algo.hashFingerprint", Map.of("inputText", input)));
    }

    @Test
    void summarizePreviewReturnsTemplateWithoutWriting() {
        String out = runner.run("ai.summarizePreview", Map.of("inputText", "short note"));
        assertTrue(out.startsWith("[ai.summarizePreview] model-gateway stub preview:"));
        assertTrue(out.contains("short note"));
    }

    @Test
    void blankInputTextIsRejected() {
        assertThrows(
                CapabilityRejected.class,
                () -> runner.run("algo.hashFingerprint", Map.of("inputText", "  ")));
    }

    @Test
    void unknownCapabilityIsRejected() {
        assertThrows(CapabilityRejected.class, () -> runner.run("algo.nope", Map.of("inputText", "x")));
    }
}
