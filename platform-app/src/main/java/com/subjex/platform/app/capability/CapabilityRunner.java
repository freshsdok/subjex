package com.subjex.platform.app.capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * CapabilityRunner — 能力执行器：对检入键跑确定性/预览桩；AI 桩不写任何存储。
 * <p>
 * No vendor SDKs. AI stubs mention model-gateway for later wiring and return preview text only.
 * 无厂商 SDK。AI 桩注明日后经 model-gateway；仅返回预览文本。
 */
public final class CapabilityRunner {

    private final CapabilityCatalog catalog;

    public CapabilityRunner(CapabilityCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
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
            case AI_SUMMARIZE_PREVIEW -> summarizePreview(inputText);
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
     * Fixed template preview; does not call model-gateway or mutate stores.
     * 固定模板预览；不调网关、不写库。
     */
    private static String summarizePreview(String inputText) {
        String clipped = inputText.length() > 80 ? inputText.substring(0, 80) + "…" : inputText;
        return "[ai.summarizePreview] model-gateway stub preview: " + clipped;
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
