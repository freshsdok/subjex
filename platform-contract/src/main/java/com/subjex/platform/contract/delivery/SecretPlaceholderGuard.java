package com.subjex.platform.contract.delivery;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * SecretPlaceholderGuard — 非 {@code local} 启动时拒绝 change-me 类占位密钥。
 * <p>
 * Covers {@code OUTBOX_HMAC_SECRET}, {@code PLATFORM_MFA_ENCRYPTION_KEY}, and (when set)
 * previous-key overlap values. Console {@code OPERATOR_SESSION_SECRET} is enforced in {@code web/}.
 * Does not require a database rebuild on rotation.
 * 覆盖出箱 HMAC 与 MFA 加密密钥（含重叠期旧密钥）。控制台会话密钥在 web 侧校验。轮换不要求重建库。
 */
public final class SecretPlaceholderGuard {

    private SecretPlaceholderGuard() {}

    public static boolean isLocalProfile(String[] activeProfiles) {
        if (activeProfiles == null) {
            return false;
        }
        return Arrays.stream(activeProfiles).anyMatch("local"::equals);
    }

    /** True when the value looks like a laptop placeholder / 像本机占位符时为 true。 */
    public static boolean looksLikePlaceholder(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.contains("change-me") || lower.contains("changeme");
    }

    /**
     * Refuse placeholder secrets outside {@code local}. Empty optional previous key is fine.
     * 非 local 拒绝占位密钥；可选旧密钥为空则跳过。
     */
    public static void refusePlaceholdersOutsideLocal(
            String[] activeProfiles, String name, String value, boolean required) {
        Objects.requireNonNull(name, "name");
        if (isLocalProfile(activeProfiles)) {
            return;
        }
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalStateException(name + " must be set outside the local profile");
            }
            return;
        }
        if (looksLikePlaceholder(value)) {
            throw new IllegalStateException(
                    name + " must not use a change-me placeholder outside the local profile");
        }
    }
}
