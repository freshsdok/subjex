package com.subjex.platform.app.capability;

/**
 * ModelCompletionClients — 按 mode 选择补全客户端（stub|http）。
 */
public final class ModelCompletionClients {

    private ModelCompletionClients() {}

    /**
     * Build client for {@code platform.ai.completion.mode} — 按配置模式构建客户端。
     *
     * @param mode {@code stub} (default) or {@code http}
     */
    public static ModelCompletionClient forMode(String mode, String httpBaseUrl, String httpApiKey, String httpModel) {
        String normalized = mode == null || mode.isBlank() ? "stub" : mode.strip().toLowerCase();
        return switch (normalized) {
            case "stub" -> new LocalStubModelCompletionClient();
            case "http" -> new HttpModelCompletionClient(httpBaseUrl, httpApiKey, httpModel);
            default -> throw new IllegalArgumentException(
                    "platform.ai.completion.mode must be stub or http (got " + mode + ")");
        };
    }
}
