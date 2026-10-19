package com.subjex.platform.app.uiauthz;

/**
 * UiPermissionRejected — UI 权限 Manifest 拒绝：坏 YAML、未知权限、重复 id 等失败关闭。
 * <p>
 * Fail-closed load/parse errors for the UI permission Manifest (ADR 0010).
 * UI 权限 Manifest 加载/解析失败关闭（ADR 0010）。
 */
public final class UiPermissionRejected extends RuntimeException {

    public UiPermissionRejected(String message) {
        super(message);
    }
}
