package com.subjex.platform.app.capability;

import java.util.Objects;

/**
 * ModelCompletionRequest — 一次模型补全请求（预览；不隐含写库）。
 * <p>
 * Bound to a catalog capability id. Callers must not treat {@code complete} as authorization to mutate
 * business state — that requires {@link AiWriteConfirmGate}.
 * 绑定目录能力 id。{@code complete} 不授权写业务状态；写回须经 {@link AiWriteConfirmGate}。
 */
public record ModelCompletionRequest(String capabilityId, String inputText) {

    public ModelCompletionRequest {
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(inputText, "inputText");
        if (capabilityId.isBlank()) {
            throw new IllegalArgumentException("capabilityId is blank");
        }
        if (inputText.isBlank()) {
            throw new IllegalArgumentException("inputText is blank");
        }
    }
}
