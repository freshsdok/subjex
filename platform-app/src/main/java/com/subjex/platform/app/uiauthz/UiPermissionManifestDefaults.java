package com.subjex.platform.app.uiauthz;

/**
 * UiPermissionManifestDefaults — Manifest 默认 UX 效果（ADR 0010 锁定）。
 * <p>
 * Locked: menu grey; button disable. Unknown effect strings fail-closed at load.
 * 锁定：菜单置灰；按钮禁用；未知 effect 加载失败关闭。
 */
public record UiPermissionManifestDefaults(String lockedMenuEffect, String missingButtonEffect) {

    public static final String EFFECT_GREY = "grey";
    public static final String EFFECT_DISABLE = "disable";

    public UiPermissionManifestDefaults {
        lockedMenuEffect = lockedMenuEffect == null || lockedMenuEffect.isBlank()
                ? EFFECT_GREY
                : lockedMenuEffect.trim();
        missingButtonEffect = missingButtonEffect == null || missingButtonEffect.isBlank()
                ? EFFECT_DISABLE
                : missingButtonEffect.trim();
        if (!EFFECT_GREY.equals(lockedMenuEffect)) {
            throw new UiPermissionRejected(
                    "lockedMenuEffect must be grey (ADR 0010 lock); got " + lockedMenuEffect);
        }
        if (!EFFECT_DISABLE.equals(missingButtonEffect)) {
            throw new UiPermissionRejected(
                    "missingButtonEffect must be disable (ADR 0010 lock); got " + missingButtonEffect);
        }
    }

    public static UiPermissionManifestDefaults lockedDefaults() {
        return new UiPermissionManifestDefaults(EFFECT_GREY, EFFECT_DISABLE);
    }
}
