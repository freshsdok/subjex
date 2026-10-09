package com.subjex.form.render;

/**
 * SideEffectKey — 副作用键：检入目录允许的枚举键，与 YAML 目录一一对应。
 * <p>
 * Forms declare these keys under {@code effects}. Unknown strings are rejected (fail-closed).
 * Tenant declaration drafts (RT-3) may only use {@link #AUDIT_WRITE} and {@link #TASK_ENQUEUE};
 * {@link #EXTENSION_INVOKE} remains classpath / platform-sample only.
 * 表单在 {@code effects} 下声明这些键。未知字符串一律拒绝（失败关闭）。
 * 租户声明草稿仅允许审计与任务入队；扩展调用仍限 classpath 样例。
 */
public enum SideEffectKey {
    /** Write one audit entry via AuditPort — 经 AuditPort 写一条审计。 */
    AUDIT_WRITE("audit.write"),
    /** Enqueue a task via TaskMessagePort — 经 TaskMessagePort 入队一条任务。 */
    TASK_ENQUEUE("task.enqueue"),
    /** Resolve a compiled-in PlatformExtension by name — 按名解析编译进进程的扩展。 */
    EXTENSION_INVOKE("extension.invoke");

    private final String key;

    SideEffectKey(String key) {
        this.key = key;
    }

    /** Catalog / YAML key string — 目录 / YAML 键字符串。 */
    public String key() {
        return key;
    }

    /**
     * Whether tenant drafts / promote may declare this effect (RT-3 whitelist) —
     * 租户草稿/晋升是否可声明该副作用（RT-3 白名单）。
     */
    public boolean allowedOnTenantDraft() {
        return this == AUDIT_WRITE || this == TASK_ENQUEUE;
    }

    /**
     * Parse a catalog key or reject — 解析目录键，否则拒绝。
     */
    public static SideEffectKey parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new FormDefinitionRejected("effect key is missing");
        }
        for (SideEffectKey known : values()) {
            if (known.key.equals(raw)) {
                return known;
            }
        }
        throw new FormDefinitionRejected("unknown effect key " + raw);
    }
}
