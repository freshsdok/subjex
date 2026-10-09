package com.subjex.platform.app.capability;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * ModelCompletionClientsTest — stub|http 选择；http 缺密钥失败关闭。
 */
class ModelCompletionClientsTest {

    @Test
    void defaultAndStubAreLocal() {
        assertInstanceOf(LocalStubModelCompletionClient.class, ModelCompletionClients.forMode(null, "", "", ""));
        assertInstanceOf(LocalStubModelCompletionClient.class, ModelCompletionClients.forMode("stub", "", "", ""));
        assertInstanceOf(LocalStubModelCompletionClient.class, ModelCompletionClients.forMode("STUB", "", "", ""));
    }

    @Test
    void httpModeWithoutKeyFailsClosedOnComplete() {
        ModelCompletionClient client = ModelCompletionClients.forMode("http", "https://example.test", "", "gpt-test");
        assertInstanceOf(HttpModelCompletionClient.class, client);
        CapabilityRejected ex = assertThrows(
                CapabilityRejected.class,
                () -> client.complete(new ModelCompletionRequest("ai.summarizePreview", "hello")));
        assertTrue(ex.getMessage().contains("SUBJEX_AI_HTTP_API_KEY"));
    }

    @Test
    void httpModeWithSecretsRefusesLiveTransportInThisBuild() {
        ModelCompletionClient client =
                ModelCompletionClients.forMode("http", "https://example.test", "secret-key", "gpt-test");
        CapabilityRejected ex = assertThrows(
                CapabilityRejected.class,
                () -> client.complete(new ModelCompletionRequest("ai.summarizePreview", "hello")));
        assertTrue(ex.getMessage().contains("transport not enabled"));
    }

    @Test
    void unknownModeRejected() {
        assertThrows(IllegalArgumentException.class, () -> ModelCompletionClients.forMode("openai", "", "", ""));
    }
}
