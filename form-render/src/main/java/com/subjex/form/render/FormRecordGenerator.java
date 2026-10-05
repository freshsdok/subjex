package com.subjex.form.render;

import java.util.stream.Collectors;

/**
 * FormRecordGenerator — 表单记录生成器：按已渲染表单的字段写出一个 Java 记录。
 * <p>
 * The caller checks the result into the repository. The build does not run this as an annotation processor.
 * 调用方把结果检入仓库。构建不把它当成注解处理器来跑。
 */
public final class FormRecordGenerator {

    /**
     * @return Java source for the record / 这条记录的 Java 源码
     */
    public String source(RenderedForm form, String packageName) {
        String components = form.fields().stream()
                .map(field -> javaType(field) + " " + field.name())
                .collect(Collectors.joining(", "));
        return "package " + packageName + ";\n"
                + "\n"
                + "/**\n"
                + " * " + form.recordName() + " — " + form.titleZh()
                + "：从表单 " + form.formKey() + " 生成的记录，已检入仓库。\n"
                + " * <p>\n"
                + " * " + form.titleEn() + ". Checked in so the build does not run an annotation processor.\n"
                + " * 检入仓库，构建时不需要注解处理器。\n"
                + " */\n"
                + "public record " + form.recordName() + "(" + components + ") {\n"
                + "}\n";
    }

    private static String javaType(FormField field) {
        if (field.kind() == FieldKind.TEXT) {
            return "String";
        }
        return field.required() ? "int" : "Integer";
    }
}
