package com.subjex.platform.app.form;

import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import com.subjex.language.PageLanguage;
import com.subjex.language.PageTitleCatalog;
import com.subjex.platform.app.view.OperatorPage;
import com.subjex.skin.NamedSkin;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
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
    public ResponseEntity<String> page(HttpServletRequest request) {
        Locale locale = OperatorPage.locale(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html(catalog.publication(), OperatorPage.title(PageTitleCatalog.FORMS, locale), OperatorPage.skin(request), locale));
    }

    static String html(RenderedForm form) {
        return html(form, "字段列表", NamedSkin.PLAIN, PageLanguage.CHINESE);
    }

    /**
     * Same field list, with the request language and skin.
     * 同一份字段列表，带上这次请求的语言和外观。
     */
    static String html(RenderedForm form, String title, NamedSkin skin, Locale locale) {
        StringBuilder body = new StringBuilder();
        OperatorPage.open(body, title, locale, skin);
        body.append("<p class=\"intro\">").append(NOT_A_DESIGNER_ZH)
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
                """);
        OperatorPage.close(body);
        return body.toString();
    }

    private static String kindZh(FieldKind kind) {
        return switch (kind) {
            case TEXT -> "文本";
            case INTEGER -> "整数";
            case BOOLEAN -> "布尔";
            case DATE -> "日期";
            case ENUM -> "枚举";
        };
    }

    private static String kindEn(FieldKind kind) {
        return switch (kind) {
            case TEXT -> "text";
            case INTEGER -> "integer";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            case ENUM -> "enum";
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
