package com.subjex.platform.app.capability;

import java.util.Objects;

/**
 * ModelCompletionResult — 补全结果：文本 + 计量摘要；{@code stub=true} 表示本地桩。
 * <p>
 * Preview only. Persist/write-back needs a confirm ticket from {@link AiWriteConfirmGate}.
 * 仅预览。落库/写回须持有确认票。
 */
public record ModelCompletionResult(
        String text, String providerId, String modelId, String inputDigest, boolean stub) {

    public ModelCompletionResult {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(inputDigest, "inputDigest");
        if (providerId.isBlank() || modelId.isBlank() || inputDigest.isBlank()) {
            throw new IllegalArgumentException("providerId, modelId, and inputDigest must be non-blank");
        }
    }
}
