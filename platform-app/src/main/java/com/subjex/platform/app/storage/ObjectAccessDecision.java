package com.subjex.platform.app.storage;

/**
 * ObjectAccessDecision — deny-by-default outcome for authorized object access (ADR 0015 / WP3-1).
 * 授权对象访问的默认拒绝结论。
 */
public enum ObjectAccessDecision {
    ALLOW,
    DENY
}
