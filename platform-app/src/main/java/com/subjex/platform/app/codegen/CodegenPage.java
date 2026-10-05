package com.subjex.platform.app.codegen;

import com.subjex.form.generated.EndpointPublication;
import com.subjex.form.render.FormField;
import com.subjex.form.render.FormRecordGenerator;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.form.FormCatalog;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CodegenPage — 生成类型页：一页只读 HTML，人能看懂从表单生成的记录和每个组件的类型。
 * <p>
 * One sentence says this page shows the type generated from the form, not a generator you run
 * from the browser. The record name and each component follow. There is no button that writes a file.
 * 开头一句话说明这一页展示从表单生成的类型，不是在浏览器里运行的生成器。
 * 接着是记录名和每个组件。没有写文件的按钮。
 */
@RestController
public class CodegenPage {

    /** Browser path — 浏览器路径。 */
    public static final String PATH = "/codegen";

    /** The only sentence on the page, in Chinese — 这一页唯一的说明句。 */
    static final String NOT_A_BROWSER_GENERATOR_ZH =
            "这一页展示从表单生成的类型，不是在浏览器里运行的生成器。";

    /** The only sentence on the page, in English. */
    static final String NOT_A_BROWSER_GENERATOR_EN =
            "This page shows the type generated from the form, not a generator you run from the browser.";

    private final FormCatalog catalog;

    public CodegenPage(FormCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping(path = PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html(catalog.publication()));
    }

    /**
     * HTML for the type the checked-in record and the generator agree on.
     * 已检入记录与生成器一致时的类型页。
     */
    static String html(RenderedForm form) {
        GeneratedType type = GeneratedType.agree(form);
        StringBuilder body = new StringBuilder();
        body.append("<!DOCTYPE html>\n")
                .append("<html lang=\"zh-Hans\">\n")
                .append("<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<title>生成类型</title>\n")
                .append("<style>\n")
                .append("  body { font-family: system-ui, sans-serif; margin: 2rem; max-width: 52rem; color: #1c1c1c; line-height: 1.4; }\n")
                .append("  table { border-collapse: collapse; width: 100%; margin-top: 1rem; }\n")
                .append("  th, td { text-align: left; padding: 0.6rem 0.75rem; border-bottom: 1px solid #ddd; vertical-align: top; }\n")
                .append("  .en { display: block; color: #555; font-size: 0.85rem; font-weight: normal; }\n")
                .append("  .intro { margin: 0; }\n")
                .append("  h1 { font-size: 1.4rem; font-weight: 600; margin: 1.25rem 0 0; }\n")
                .append("  code { font-size: 0.95rem; }\n")
                .append("</style>\n")
                .append("</head>\n")
                .append("<body>\n")
                .append("<p class=\"intro\">").append(NOT_A_BROWSER_GENERATOR_ZH)
                .append("<span class=\"en\">").append(NOT_A_BROWSER_GENERATOR_EN).append("</span></p>\n")
                .append("<h1>记录 ").append(escape(type.recordName()))
                .append("<span class=\"en\">record ").append(escape(type.recordName())).append("</span></h1>\n");
        body.append("""
                <table>
                <thead>
                <tr>
                <th>组件<span class="en">component</span></th>
                <th>类型<span class="en">type</span></th>
                </tr>
                </thead>
                <tbody>
                """);
        for (GeneratedComponent component : type.components()) {
            body.append("<tr><td><code>")
                    .append(escape(component.name()))
                    .append("</code></td><td>")
                    .append(escape(component.typeZh()))
                    .append("<span class=\"en\">")
                    .append(escape(component.typeEn()))
                    .append("</span></td></tr>\n");
        }
        body.append("""
                </tbody>
                </table>
                </body>
                </html>
                """);
        return body.toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * One generated record the page may show.
     * 页面可以展示的一条已生成记录。
     */
    record GeneratedType(String recordName, List<GeneratedComponent> components) {

        /**
         * Read components from the checked-in record, and refuse them unless
         * {@link FormRecordGenerator} writes the same signature for this form.
         * 从已检入的记录读组件；现有生成器为这份表单写出的签名不一致时拒绝。
         */
        static GeneratedType agree(RenderedForm form) {
            String recordName = EndpointPublication.class.getSimpleName();
            if (!recordName.equals(form.recordName())) {
                throw new IllegalStateException("checked-in record name does not match the form");
            }
            String source = new FormRecordGenerator().source(form, EndpointPublication.class.getPackageName());
            RecordComponent[] checkedIn = EndpointPublication.class.getRecordComponents();
            List<FormField> fields = form.fields();
            if (checkedIn.length != fields.size()) {
                throw new IllegalStateException("checked-in record does not match the form fields");
            }
            String signature = signature(source, recordName);
            List<GeneratedComponent> components = new ArrayList<>();
            for (int index = 0; index < checkedIn.length; index++) {
                FormField field = fields.get(index);
                RecordComponent component = checkedIn[index];
                if (!field.name().equals(component.getName())) {
                    throw new IllegalStateException("component " + component.getName() + " is not " + field.name());
                }
                String javaName = javaName(component.getType());
                String declared = javaName + " " + field.name();
                if (!signature.equals(declared) && !signature.contains(declared)) {
                    throw new IllegalStateException("generator signature does not declare " + declared);
                }
                components.add(new GeneratedComponent(field.name(), typeZh(javaName), javaName));
            }
            String expected = components.stream()
                    .map(component -> component.typeEn() + " " + component.name())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("");
            if (!signature.equals(expected)) {
                throw new IllegalStateException("generator signature drifted from the checked-in record");
            }
            return new GeneratedType(recordName, List.copyOf(components));
        }

        private static String signature(String source, String recordName) {
            String header = "public record " + recordName + "(";
            int start = source.indexOf(header);
            if (start < 0) {
                throw new IllegalStateException("generator did not write " + recordName);
            }
            int open = start + header.length();
            int close = source.indexOf(')', open);
            if (close < 0) {
                throw new IllegalStateException("generator signature is incomplete");
            }
            return source.substring(open, close);
        }

        private static String javaName(Class<?> type) {
            if (type.isPrimitive() || "java.lang".equals(type.getPackageName())) {
                return type.getSimpleName();
            }
            throw new IllegalStateException("unexpected component type " + type.getName());
        }

        private static String typeZh(String javaName) {
            return switch (javaName) {
                case "String" -> "字符串";
                case "int", "Integer" -> "整数";
                default -> throw new IllegalStateException("no Chinese label for " + javaName);
            };
        }
    }

    /** One component of the generated record — 已生成记录的一个组件。 */
    record GeneratedComponent(String name, String typeZh, String typeEn) {}
}
