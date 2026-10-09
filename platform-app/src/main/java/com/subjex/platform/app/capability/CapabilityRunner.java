package com.subjex.platform.app.capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * CapabilityRunner — 能力执行器：算法确定性；AI 经 {@link ModelCompletionClient} 预览（默认本地桩）。
 * <p>
 * AI paths are preview-only — no entity/DB mutation. Write-back needs {@link AiWriteConfirmGate} (AI-3c+).
 * No hard dependency on the optional {@code model-gateway} module.
 * AI 仅预览、不写库；写回须确认门闩。不硬依赖 model-gateway。
 */
public final class CapabilityRunner {

    private final CapabilityCatalog catalog;
    private final ModelCompletionClient completionClient;

    /**
     * Tests / callers without DI: local stub completion — 无 DI 时用本地桩补全。
     */
    public CapabilityRunner(CapabilityCatalog catalog) {
        this(catalog, new LocalStubModelCompletionClient());
    }

    public CapabilityRunner(CapabilityCatalog catalog, ModelCompletionClient completionClient) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.completionClient = Objects.requireNonNull(completionClient, "completionClient");
    }

    /**
     * Run a catalog capability; returns a short result string — 执行目录能力，返回短结果串。
     */
    public String run(String capabilityId, Map<String, Object> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        CapabilitySpec spec = catalog.require(capabilityId);
        CapabilityId id = CapabilityId.parse(spec.id());
        String inputText = requiredText(inputs, "inputText");
        return switch (id) {
            case ALGO_HASH_FINGERPRINT -> hashFingerprint(inputText);
            case ALGO_NORMALIZE_WHITESPACE -> normalizeWhitespace(inputText);
            case AI_SUMMARIZE_PREVIEW, AI_SUGGEST_TITLE_PREVIEW -> completionClient
                    .complete(new ModelCompletionRequest(id.id(), inputText))
                    .text();
        };
    }

    private static String hashFingerprint(String inputText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(inputText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Trim already applied by {@link #requiredText}; collapse internal whitespace runs to single spaces.
     * requiredText 已去首尾；此处将内部连续空白压成单空格。
     */
    private static String normalizeWhitespace(String inputText) {
        return inputText.replaceAll("\\s+", " ");
    }

    private static String requiredText(Map<String, Object> inputs, String name) {
        Object raw = inputs.get(name);
        if (raw == null) {
            throw new CapabilityRejected(name + " is required");
        }
        String text = Objects.toString(raw, "").strip();
        if (text.isEmpty()) {
            throw new CapabilityRejected(name + " is required");
        }
        return text;
    }
}
