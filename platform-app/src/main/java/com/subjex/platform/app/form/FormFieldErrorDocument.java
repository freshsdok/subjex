package com.subjex.platform.app.form;

/**
 * FormFieldErrorDocument — 单个字段校验错误：字段名、机器可读 code、说明。
 * <p>
 * Codes: {@code required}, {@code too_long}, {@code below_minimum}, {@code above_maximum},
 * {@code not_integer}, {@code unsupported_kind}.
 */
public record FormFieldErrorDocument(String field, String code, String message) {}
