package com.subjex.platform.app.capability;

/**
 * ModelCompletionClient — 模型补全端口：预览文本；不写业务库。
 * <p>
 * Implementations: local stub (default), later HTTP / metered gateway. Must not mutate entity/form stores.
 * 实现：本地桩（默认），日后 HTTP/计量网关。不得改实体/表单存储。
 */
public interface ModelCompletionClient {

    /**
     * Produce preview text for a catalog AI capability — 为目录 AI 能力生成预览文本。
     */
    ModelCompletionResult complete(ModelCompletionRequest request);
}
