package com.subjex.form.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FormRecordWriteMain — 表单记录写出入口：读一份 {@code .form.yaml}，把生成的 Java record 写到目标路径。
 * <p>
 * The caller checks the result into the repository. The build does not run this as an annotation processor.
 * Usage / 用法:
 * {@code java -cp form-render.jar:snakeyaml.jar com.subjex.form.render.FormRecordWriteMain \\
 *   path/to/form.form.yaml path/to/GeneratedRecord.java}
 * Optional third argument: package name (default {@code com.subjex.form.generated}).
 * 第三个参数可选：包名（默认 {@code com.subjex.form.generated}）。
 * 调用方把结果检入仓库。构建不把它当成注解处理器来跑。
 */
public final class FormRecordWriteMain {

    private static final String DEFAULT_PACKAGE = "com.subjex.form.generated";

    private FormRecordWriteMain() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.err.println(
                    "usage: FormRecordWriteMain <form.yaml> <output.java> [packageName]");
            System.err.println(
                    "用法：FormRecordWriteMain <表单.yaml> <输出.java> [包名]");
            System.exit(2);
        }
        Path yamlPath = Path.of(args[0]);
        Path outputPath = Path.of(args[1]);
        String packageName = args.length == 3 ? args[2] : DEFAULT_PACKAGE;
        String yaml = Files.readString(yamlPath, StandardCharsets.UTF_8);
        RenderedForm form = new FormRenderer().render(yaml);
        String source = new FormRecordGenerator().source(form, packageName);
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outputPath, source, StandardCharsets.UTF_8);
        System.out.println("wrote " + outputPath.toAbsolutePath() + " (" + form.recordName() + ")");
    }
}
