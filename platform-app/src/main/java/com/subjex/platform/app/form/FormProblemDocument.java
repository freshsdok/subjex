package com.subjex.platform.app.form;

import java.util.List;

/**
 * FormProblemDocument — 表单/流程提交的结构化失败体：校验或权限拒绝，控制台调试面板同处展示。
 * <p>
 * {@code kind} is {@code validation} (with {@code fieldErrors}) or {@code permission_denied}
 * (with {@code permission}). {@code message} is a short human-readable summary.
 * {@code kind} 为 {@code validation}（带 {@code fieldErrors}）或 {@code permission_denied}
 * （带 {@code permission}）。{@code message} 是短摘要。
 */
public record FormProblemDocument(
        String kind,
        List<FormFieldErrorDocument> fieldErrors,
        String permission,
        String message) {}
