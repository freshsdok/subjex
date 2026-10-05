package com.subjex.platform.app.form;

/**
 * EffectOutcomeDocument — 一次声明副作用的执行摘要：键与结果（如 {@code ok}）。
 */
public record EffectOutcomeDocument(String key, String outcome) {}
