package com.subjex.form.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FormRecordWriteMainTest — CLI 写出测试：把 YAML 渲染成 Java 源文件写到目标路径。
 */
class FormRecordWriteMainTest {

    @TempDir
    Path temp;

    @Test
    void writesGeneratedSourceToTheOutputPath() throws Exception {
        Path yaml = temp.resolve("sample.form.yaml");
        Files.writeString(
                yaml,
                """
                formKey: sample-form
                titleEn: Sample
                titleZh: 样例
                fields:
                  - name: label
                    kind: text
                    required: true
                    maxLength: 32
                """);
        Path output = temp.resolve("out").resolve("SampleForm.java");
        FormRecordWriteMain.main(new String[] {yaml.toString(), output.toString()});
        assertTrue(Files.isRegularFile(output));
        String expected = new FormRecordGenerator()
                .source(new FormRenderer().render(Files.readString(yaml)), "com.subjex.form.generated");
        assertEquals(expected, Files.readString(output));
    }
}
