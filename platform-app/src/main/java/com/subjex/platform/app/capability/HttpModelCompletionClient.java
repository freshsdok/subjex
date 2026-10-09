package com.subjex.platform.app.capability;

import java.util.Objects;

/**
 * HttpModelCompletionClient — HTTP 补全适配器（AI-3b 脚手架）：需 API key；默认测试走 stub 模式。
 * <p>
 * Env (see {@code platform.ai.completion.*} / docs/ai/capability-real-ai-plan.md):
 * {@code SUBJEX_AI_HTTP_BASE_URL}, {@code SUBJEX_AI_HTTP_API_KEY}, {@code SUBJEX_AI_HTTP_MODEL}.
 * Does not call vendors until a later slice enables a real transport; missing key → fail-closed.
 * Does not write business state.
 * 缺 key 失败关闭；本片不发起真实厂商调用；不写业务库。
 */
public final class HttpModelCompletionClient implements ModelCompletionClient {

    private final String baseUrl;
    private final String apiKey;
    private final String modelId;

    public HttpModelCompletionClient(String baseUrl, String apiKey, String modelId) {
        this.baseUrl = Objects.requireNonNullElse(baseUrl, "").strip();
        this.apiKey = Objects.requireNonNullElse(apiKey, "").strip();
        this.modelId = Objects.requireNonNullElse(modelId, "").strip();
    }

    @Override
    public ModelCompletionResult complete(ModelCompletionRequest request) {
        Objects.requireNonNull(request, "request");
        CapabilityId.parse(request.capabilityId());
        if (apiKey.isEmpty()) {
            throw new CapabilityRejected(
                    "HTTP AI completion requires SUBJEX_AI_HTTP_API_KEY (platform.ai.completion.mode=http)");
        }
        if (baseUrl.isEmpty()) {
            throw new CapabilityRejected(
                    "HTTP AI completion requires SUBJEX_AI_HTTP_BASE_URL (platform.ai.completion.mode=http)");
        }
        if (modelId.isEmpty()) {
            throw new CapabilityRejected(
                    "HTTP AI completion requires SUBJEX_AI_HTTP_MODEL (platform.ai.completion.mode=http)");
        }
        // 3b: refuse live vendor I/O — keep CI free of paid calls; wire transport in a later slice.
        // 3b：拒绝真实外呼，避免 CI 扣费；传输层后片再接。
        throw new CapabilityRejected(
                "HTTP AI completion transport not enabled in this build (mode=http configured; use mode=stub for local preview)");
    }

    /** Visible for tests — 测试可见。 */
    String apiKeyConfigured() {
        return apiKey.isEmpty() ? "" : "(set)";
    }
}
