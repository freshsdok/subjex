package com.subjex.platform.app.security;

/**
 * AccessAction — 访问判定中的动作名（如 read / submit / write）。
 * <p>
 * Not a role name; only labels the attempted operation for explainable decisions.
 * 不是角色名；只给可解释判定标注试图执行的操作。
 */
public record AccessAction(String name) {

    public AccessAction {
        name = name == null ? "" : name.trim();
    }

    public static AccessAction of(String name) {
        return new AccessAction(name);
    }
}
