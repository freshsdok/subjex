package com.subjex.form.render;

/**
 * DomainActionKey — 领域动作键：检入目录允许的枚举键，与 YAML 目录一一对应。
 * <p>
 * Forms declare one of these under {@code domainAction}. Unknown strings are rejected (fail-closed).
 * Domain actions are the primary write (register service, override config, save entity stub); side effects stay separate.
 * Capability actions invoke the thin algorithm/AI catalog (preview/deterministic stubs; AI does not write stores).
 * 表单在 {@code domainAction} 下声明其中一个键。未知字符串一律拒绝（失败关闭）。
 * 领域动作是主写入；副作用仍单独声明。能力动作挂接薄算法/AI 目录（确定性/预览桩；AI 不写库）。
 */
public enum DomainActionKey {
    /** Register a service endpoint via ServiceCatalog — 经 ServiceCatalog 登记服务端点。 */
    REGISTRY_REGISTER("registry.register"),
    /** Write a config override via ConfigCatalog — 经 ConfigCatalog 写配置覆盖。 */
    CONFIG_OVERRIDE("config.override"),
    /** Upsert a generic entity record by form entityKey — 按表单 entityKey 写入通用实体记录。 */
    ENTITY_RECORD_UPSERT("entity.record.upsert"),
    /** Deterministic SHA-256 fingerprint of inputText — inputText 的确定性 SHA-256 指纹。 */
    CAPABILITY_ALGO_HASH_FINGERPRINT("capability.algo.hashFingerprint"),
    /** Deterministic whitespace normalize of inputText — inputText 确定性空白归一化。 */
    CAPABILITY_ALGO_NORMALIZE_WHITESPACE("capability.algo.normalizeWhitespace"),
    /** AI summarize preview stub (no store write) — AI 摘要预览桩（不写库）。 */
    CAPABILITY_AI_SUMMARIZE_PREVIEW("capability.ai.summarizePreview"),
    /** AI title suggestion preview stub (no store write) — AI 标题建议预览桩（不写库）。 */
    CAPABILITY_AI_SUGGEST_TITLE_PREVIEW("capability.ai.suggestTitlePreview");

    private final String key;

    DomainActionKey(String key) {
        this.key = key;
    }

    /** Catalog / YAML key string — 目录 / YAML 键字符串。 */
    public String key() {
        return key;
    }

    /**
     * Parse a catalog key or reject — 解析目录键，否则拒绝。
     */
    public static DomainActionKey parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new FormDefinitionRejected("domainAction is missing");
        }
        for (DomainActionKey known : values()) {
            if (known.key.equals(raw)) {
                return known;
            }
        }
        throw new FormDefinitionRejected("unknown domainAction " + raw);
    }
}
