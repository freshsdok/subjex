package com.subjex.platform.app.form;

import java.util.List;
import java.util.Objects;

/**
 * FormValidationException — 表单字段校验失败：携带逐字段错误，供控制台调试面板一次展示。
 * <p>
 * Thrown when one or more declared fields fail required / kind / range checks.
 * 当一个或多个声明字段未通过必填 / 类型 / 范围检查时抛出。
 */
public final class FormValidationException extends RuntimeException {

    private final List<FormFieldErrorDocument> fieldErrors;

    public FormValidationException(List<FormFieldErrorDocument> fieldErrors) {
        super(summarize(fieldErrors));
        this.fieldErrors = List.copyOf(Objects.requireNonNull(fieldErrors, "fieldErrors"));
        if (this.fieldErrors.isEmpty()) {
            throw new IllegalArgumentException("fieldErrors must not be empty");
        }
    }

    public List<FormFieldErrorDocument> fieldErrors() {
        return fieldErrors;
    }

    private static String summarize(List<FormFieldErrorDocument> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return "validation failed";
        }
        return fieldErrors.get(0).message();
    }
}
