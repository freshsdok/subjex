package com.subjex.form.render;

import java.util.List;

/**
 * RenderedForm — 已渲染表单：表单键、标题，以及校验过的字段列表。
 * <p>
 * {@code recordName} is the Java record simple name a generator would write for this form.
 * {@code recordName} 是生成器为这份表单写下的 Java 记录简单名。
 */
public record RenderedForm(
        String formKey,
        String titleEn,
        String titleZh,
        String recordName,
        List<FormField> fields) {

    public RenderedForm {
        fields = List.copyOf(fields);
    }
}
