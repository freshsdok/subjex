package com.subjex.platform.app.capability;

/**
 * CapabilityKind — 能力种类：算法（确定性）或 AI（经 model-gateway 概念，首波仅为预览桩）。
 */
public enum CapabilityKind {
    /** Deterministic catalog algorithm — 确定性目录算法。 */
    ALGORITHM,
    /** AI via model-gateway concepts; stubs preview only — AI 走网关概念；桩仅预览。 */
    AI
}
