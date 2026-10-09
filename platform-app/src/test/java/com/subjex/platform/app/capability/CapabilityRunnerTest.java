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
 * CapabilityRunnerTest — 能力：哈希/空白归一确定性；AI 经 ModelCompletionClient（默认本地桩）。
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
    void normalizeWhitespaceTrimsAndCollapses() {
        assertEquals(
                "hello world",
                runner.run("algo.normalizeWhitespace", Map.of("inputText", "  hello   world  ")));
        assertEquals(
                "a b c",
                runner.run("algo.normalizeWhitespace", Map.of("inputText", "a\t\nb   c")));
    }

    @Test
    void summarizePreviewReturnsTemplateWithoutWriting() {
        String out = runner.run("ai.summarizePreview", Map.of("inputText", "short note"));
        assertTrue(out.startsWith("[ai.summarizePreview] model-gateway stub preview:"));
        assertTrue(out.contains("short note"));
    }

    @Test
    void suggestTitlePreviewUsesFirstLineOrSixtyChars() {
        String multi = runner.run("ai.suggestTitlePreview", Map.of("inputText", "First line\nSecond line"));
        assertEquals("[ai.suggestTitlePreview] model-gateway stub title: First line", multi);
        String longLine = "x".repeat(70);
        String clipped = runner.run("ai.suggestTitlePreview", Map.of("inputText", longLine));
        assertTrue(clipped.startsWith("[ai.suggestTitlePreview] model-gateway stub title: "));
        assertTrue(clipped.endsWith("…"));
        assertEquals(60 + "[ai.suggestTitlePreview] model-gateway stub title: ".length() + 1, clipped.length());
    }

    @Test
    void blankInputTextIsRejected() {
        assertThrows(
                CapabilityRejected.class,
                () -> runner.run("algo.hashFingerprint", Map.of("inputText", "  ")));
        assertThrows(
                CapabilityRejected.class,
                () -> runner.run("algo.normalizeWhitespace", Map.of("inputText", "\t")));
    }

    @Test
    void unknownCapabilityIsRejected() {
        assertThrows(CapabilityRejected.class, () -> runner.run("algo.nope", Map.of("inputText", "x")));
    }

    @Test
    void routesAiThroughInjectedClient() {
        ModelCompletionClient fake = request -> new ModelCompletionResult(
                "FAKE-SUMMARY:" + request.inputText(),
                "test",
                "fake-model",
                LocalStubModelCompletionClient.sha256Hex(request.inputText()),
                true);
        CapabilityRunner wired = new CapabilityRunner(new CapabilityCatalog(), fake);
        assertEquals(
                "FAKE-SUMMARY:note",
                wired.run("ai.summarizePreview", Map.of("inputText", "note")));
        assertEquals(
                "FAKE-SUMMARY:Title only",
                wired.run("ai.suggestTitlePreview", Map.of("inputText", "Title only")));
    }
}
