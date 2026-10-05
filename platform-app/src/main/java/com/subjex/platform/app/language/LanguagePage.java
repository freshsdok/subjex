package com.subjex.platform.app.language;

import com.subjex.language.PageLanguage;
import com.subjex.language.PageTitleCatalog;
import com.subjex.platform.app.view.OperatorPage;
import com.subjex.skin.NamedSkin;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LanguagePage — 语言页：一页只读 HTML，人能看懂当前语言，以及目录里的话。
 * <p>
 * One sentence says this is not a translation desk. Then the current language,
 * then each catalog phrase in that language. The codes stay off the page.
 * 开头一句话说明这不是翻译台。然后是当前语言，再是该语言下目录里的每一句话。
 * 代号不出现在页面上。
 */
@RestController
public class LanguagePage {

    /** Browser path — 浏览器路径。 */
    public static final String PATH = "/language";

    /** The only sentence on the page, in Chinese — 这一页唯一的说明句。 */
    static final String NOT_AN_EDITOR_ZH =
            "这是只读的语言页：先写当前语言，再把目录里的话写成能读的句子，不是翻译编辑台。";

    /** The only sentence on the page, in English. */
    static final String NOT_AN_EDITOR_EN =
            "This is a read-only language page: the current language, then the catalog in plain words, not a translation editor.";

    @GetMapping(path = PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(HttpServletRequest request) {
        Locale locale = OperatorPage.locale(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html(locale, OperatorPage.skin(request)));
    }

    /**
     * HTML for one language. Phrases come from the catalog; codes are not written out.
     * 一种语言的页面。话来自目录；代号不写出来。
     */
    static String html(Locale locale, NamedSkin skin) {
        StringBuilder body = new StringBuilder();
        OperatorPage.open(body, OperatorPage.title(PageTitleCatalog.LANGUAGE, locale), locale, skin);
        body.append("<p class=\"intro\">").append(NOT_AN_EDITOR_ZH)
                .append("<span class=\"en\">").append(NOT_AN_EDITOR_EN).append("</span></p>\n")
                .append("<p>当前语言<span class=\"en\">current language</span>：")
                .append(OperatorPage.escape(PageLanguage.name(locale)))
                .append("</p>\n<ul>\n");
        for (String code : PageTitleCatalog.CODES) {
            body.append("<li>")
                    .append(OperatorPage.escape(OperatorPage.title(code, locale)))
                    .append("</li>\n");
        }
        body.append("</ul>\n");
        OperatorPage.close(body);
        return body.toString();
    }
}
