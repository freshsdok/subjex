package com.subjex.platform.app.form;

import com.subjex.platform.app.security.OrgScope;
import java.util.List;

/**
 * FormProblemDocument — 表单/流程提交的结构化失败体：校验或权限拒绝，控制台调试面板同处展示。
 * <p>
 * {@code kind} is {@code validation} (with {@code fieldErrors}) or {@code permission_denied}
 * (with {@code permission} for backward compatibility plus explainable decision fields).
 * {@code orgScope} is the structured scope (mode + roots + unitIds) when present.
 * {@code kind} 为 {@code validation}（带 {@code fieldErrors}）或 {@code permission_denied}
 * （保留 {@code permission}，并带可解释判定字段）。{@code orgScope} 有则带模式与单元集合。
 */
public record FormProblemDocument(
        String kind,
        List<FormFieldErrorDocument> fieldErrors,
        String permission,
        String message,
        String subjectId,
        String tenantId,
        OrgScope orgScope,
        String resourceKind,
        String resourceId,
        String action,
        String matchedPermission,
        Boolean allowed,
        String denyReason) {

    /** Validation / legacy helper without decision fields — 无决策字段的校验/兼容构造。 */
    public FormProblemDocument(
            String kind, List<FormFieldErrorDocument> fieldErrors, String permission, String message) {
        this(kind, fieldErrors, permission, message, null, null, null, null, null, null, null, null, null);
    }
}
