package com.subjex.platform.app.form;

import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FormsPage — 字段列表页：一页只读 HTML，人能看懂字段名、类型和是否必填。
 * <p>
 * One sentence says this is the field list, not a designer. Each row is a field name,
 * its type, and whether it is required. There is no drag-and-drop designer and no submit button.
 * 开头一句话说明这是字段列表，不是设计器。每一行是字段名、类型，以及是否必填。
 * 没有拖拽设计器，也没有提交按钮。
 */
@RestController
public class FormsPage {

    /** Browser path — 浏览器路径。 */
    public static final String PATH = "/forms";

    /** The only sentence on the page, in Chinese — 这一页唯一的说明句。 */
    static final String NOT_A_DESIGNER_ZH = "这是字段列表，不是设计器。";

    /** The only sentence on the page, in English. */
    static final String NOT_A_DESIGNER_EN = "This is the field list, not a designer.";

    private final FormCatalog catalog;

    public FormsPage(FormCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping(path = PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html(catalog.publication()));
    }

    static String html(RenderedForm form) {
        StringBuilder body = new StringBuilder();
        body.append("<!DOCTYPE html>\n")
                .append("<html lang=\"zh-Hans\">\n")
                .append("<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<title>字段列表</title>\n")
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
                .append("<p class=\"intro\">").append(NOT_A_DESIGNER_ZH)
                .append("<span class=\"en\">").append(NOT_A_DESIGNER_EN).append("</span></p>\n")
                .append("<h1>").append(escape(form.titleZh()))
                .append("<span class=\"en\">").append(escape(form.titleEn())).append("</span></h1>\n");
        body.append("""
                <table>
                <thead>
                <tr>
                <th>字段<span class="en">name</span></th>
                <th>类型<span class="en">type</span></th>
                <th>是否必填<span class="en">required</span></th>
                </tr>
                </thead>
                <tbody>
                """);
        for (FormField field : form.fields()) {
            body.append("<tr><td><code>")
                    .append(escape(field.name()))
                    .append("</code></td><td>")
                    .append(kindZh(field.kind()))
                    .append("<span class=\"en\">")
                    .append(kindEn(field.kind()))
                    .append("</span></td><td>")
                    .append(requiredZh(field.required()))
                    .append("<span class=\"en\">")
                    .append(requiredEn(field.required()))
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

    private static String kindZh(FieldKind kind) {
        return switch (kind) {
            case TEXT -> "文本";
            case INTEGER -> "整数";
        };
    }

    private static String kindEn(FieldKind kind) {
        return switch (kind) {
            case TEXT -> "text";
            case INTEGER -> "integer";
        };
    }

    private static String requiredZh(boolean required) {
        return required ? "必填" : "选填";
    }

    private static String requiredEn(boolean required) {
        return required ? "required" : "optional";
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
}
