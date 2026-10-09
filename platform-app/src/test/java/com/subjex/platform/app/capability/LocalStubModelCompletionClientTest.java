package com.subjex.platform.app.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * LocalStubModelCompletionClientTest — 本地桩补全与现有 AI 模板对齐；拒算法键。
 */
class LocalStubModelCompletionClientTest {

    private final LocalStubModelCompletionClient client = new LocalStubModelCompletionClient();

    @Test
    void summarizeMatchesRunnerTemplate() {
        ModelCompletionResult out =
                client.complete(new ModelCompletionRequest("ai.summarizePreview", "short note"));
        assertTrue(out.stub());
        assertEquals(LocalStubModelCompletionClient.PROVIDER_ID, out.providerId());
        assertTrue(out.text().startsWith("[ai.summarizePreview] model-gateway stub preview:"));
        assertTrue(out.text().contains("short note"));
        assertEquals(LocalStubModelCompletionClient.sha256Hex("short note"), out.inputDigest());
    }

    @Test
    void suggestTitleMatchesRunnerTemplate() {
        ModelCompletionResult out =
                client.complete(new ModelCompletionRequest("ai.suggestTitlePreview", "First line\nSecond"));
        assertEquals("[ai.suggestTitlePreview] model-gateway stub title: First line", out.text());
    }

    @Test
    void rejectsAlgorithmCapability() {
        assertThrows(
                CapabilityRejected.class,
                () -> client.complete(new ModelCompletionRequest("algo.hashFingerprint", "x")));
    }
}
