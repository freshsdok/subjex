package com.subjex.platform.contract.exporthook;

/**
 * SensitivityClass — minimal vocabulary: {@link #NORMAL} (default) or {@link #SENSITIVE}.
 * 最小敏感级别词表：默认 {@link #NORMAL}，或 {@link #SENSITIVE}。不得把既有数据悄悄改标为敏感。
 */
public enum SensitivityClass {
    NORMAL,
    SENSITIVE;

    /**
     * Parse a stored or API value; blank becomes {@link #NORMAL}. Other tokens are rejected.
     * 解析存储或接口值；空白视为 {@link #NORMAL}。其它词拒绝。
     */
    public static SensitivityClass parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NORMAL;
        }
        String key = raw.trim();
        for (SensitivityClass value : values()) {
            if (value.name().equals(key)) {
                return value;
            }
        }
        throw new IllegalArgumentException("sensitivity must be NORMAL or SENSITIVE");
    }
}
