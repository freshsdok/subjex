package com.subjex.platform.app.capability;

/**
 * CapabilityId — 检入能力键：与 YAML 目录一一对应；未知字符串拒绝。
 */
public enum CapabilityId {
    /** SHA-256 hex of inputText — inputText 的 SHA-256 十六进制。 */
    ALGO_HASH_FINGERPRINT("algo.hashFingerprint", CapabilityKind.ALGORITHM),
    /** Trim + collapse whitespace runs — 去首尾并压扁内部空白。 */
    ALGO_NORMALIZE_WHITESPACE("algo.normalizeWhitespace", CapabilityKind.ALGORITHM),
    /** Template summarize preview; no write — 模板摘要预览；不写库。 */
    AI_SUMMARIZE_PREVIEW("ai.summarizePreview", CapabilityKind.AI),
    /** Template title suggestion preview; no write — 模板标题建议预览；不写库。 */
    AI_SUGGEST_TITLE_PREVIEW("ai.suggestTitlePreview", CapabilityKind.AI);

    private final String id;
    private final CapabilityKind kind;

    CapabilityId(String id, CapabilityKind kind) {
        this.id = id;
        this.kind = kind;
    }

    public String id() {
        return id;
    }

    public CapabilityKind kind() {
        return kind;
    }

    /**
     * Parse a catalog id or reject — 解析目录 id，否则拒绝。
     */
    public static CapabilityId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new CapabilityRejected("capability id is missing");
        }
        for (CapabilityId known : values()) {
            if (known.id.equals(raw)) {
                return known;
            }
        }
        throw new CapabilityRejected("unknown capability " + raw);
    }
}
