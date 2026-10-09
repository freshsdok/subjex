package com.subjex.platform.app.capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * LocalStubModelCompletionClient — 本地桩补全：无外呼、无 API key；与现有模板预览对齐。
 * <p>
 * First green path for item 3. Does not depend on the optional {@code model-gateway} module.
 * 项 3 首条绿路径。不依赖可选 model-gateway 模块。
 */
public final class LocalStubModelCompletionClient implements ModelCompletionClient {

    public static final String PROVIDER_ID = "local-stub";
    public static final String MODEL_ID = "stub-template-v1";

    @Override
    public ModelCompletionResult complete(ModelCompletionRequest request) {
        Objects.requireNonNull(request, "request");
        CapabilityId id = CapabilityId.parse(request.capabilityId());
        if (id.kind() != CapabilityKind.AI) {
            throw new CapabilityRejected("ModelCompletionClient is for AI capabilities only: " + id.id());
        }
        String input = request.inputText().strip();
        String text =
                switch (id) {
                    case AI_SUMMARIZE_PREVIEW -> summarize(input);
                    case AI_SUGGEST_TITLE_PREVIEW -> suggestTitle(input);
                    case ALGO_HASH_FINGERPRINT, ALGO_NORMALIZE_WHITESPACE -> throw new CapabilityRejected(
                            "not an AI capability: " + id.id());
                };
        return new ModelCompletionResult(text, PROVIDER_ID, MODEL_ID, sha256Hex(input), true);
    }

    private static String summarize(String inputText) {
        String clipped = inputText.length() > 80 ? inputText.substring(0, 80) + "…" : inputText;
        return "[ai.summarizePreview] model-gateway stub preview: " + clipped;
    }

    private static String suggestTitle(String inputText) {
        String firstLine = inputText;
        int newline = inputText.indexOf('\n');
        if (newline >= 0) {
            firstLine = inputText.substring(0, newline);
        }
        firstLine = firstLine.strip();
        String clipped = firstLine.length() > 60 ? firstLine.substring(0, 60) + "…" : firstLine;
        return "[ai.suggestTitlePreview] model-gateway stub title: " + clipped;
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
