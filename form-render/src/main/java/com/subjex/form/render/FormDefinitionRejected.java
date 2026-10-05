package com.subjex.form.render;

/**
 * FormDefinitionRejected — 表单定义被拒绝：声明式表单本身不合法。
 * <p>
 * This is not a rejected submission. Nothing was stored.
 * 这不是一次被拒绝的提交。没有保存任何东西。
 */
public final class FormDefinitionRejected extends IllegalArgumentException {

    public FormDefinitionRejected(String message) {
        super(message);
    }
}
