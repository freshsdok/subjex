package com.subjex.platform.app.skin;

import com.subjex.language.PageTitleCatalog;
import com.subjex.platform.app.view.OperatorPage;
import com.subjex.skin.NamedSkin;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SkinPage — 外观页：一页只读 HTML，人能看懂当前外观，并用三个名字换上。
 * <p>
 * One sentence says this is not a color picker. A link with a known name sets a cookie.
 * The page does not list the CSS variables.
 * 开头一句话说明这不是取色器。点一个认得出的名字会写入 cookie。
 * 页面不列出 CSS 变量。
 */
@RestController
public class SkinPage {

    /** Browser path — 浏览器路径。 */
    public static final String PATH = "/skin";

    /** The only sentence on the page, in Chinese — 这一页唯一的说明句。 */
    static final String NOT_A_PICKER_ZH =
            "这是三个具名外观，点一个名字就换上。不是取色器，也不把颜色变量列出来。";

    /** The only sentence on the page, in English. */
    static final String NOT_A_PICKER_EN =
            "These are three named skins. Choose a name to switch. This is not a color picker, and it does not list color variables.";

    @GetMapping(path = PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(HttpServletRequest request) {
        NamedSkin selected = OperatorPage.skin(request);
        Locale locale = OperatorPage.locale(request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"));
        NamedSkin requested = NamedSkin.parse(request.getParameter("skin"));
        if (requested != null) {
            ResponseCookie cookie = ResponseCookie.from(OperatorPage.SKIN_COOKIE, requested.token())
                    .path("/")
                    .httpOnly(true)
                    .sameSite("Lax")
                    .maxAge(Duration.ofDays(180))
                    .build();
            builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return builder.body(html(selected, locale));
    }

    /**
     * HTML for the current skin and the three choices.
     * 当前外观和三个选择的页面。
     */
    static String html(NamedSkin selected, Locale locale) {
        StringBuilder body = new StringBuilder();
        OperatorPage.open(body, OperatorPage.title(PageTitleCatalog.SKIN, locale), locale, selected);
        body.append("<p class=\"intro\">").append(NOT_A_PICKER_ZH)
                .append("<span class=\"en\">").append(NOT_A_PICKER_EN).append("</span></p>\n")
                .append("<p>当前外观<span class=\"en\">current skin</span>：")
                .append(OperatorPage.escape(selected.chinese()))
                .append("<span class=\"en\">")
                .append(OperatorPage.escape(selected.english()))
                .append("</span></p>\n<ul>\n");
        for (NamedSkin skin : NamedSkin.values()) {
            body.append("<li><a href=\"/skin?skin=")
                    .append(skin.token())
                    .append("\">")
                    .append(OperatorPage.escape(skin.chinese()))
                    .append("<span class=\"en\">")
                    .append(OperatorPage.escape(skin.english()))
                    .append("</span></a></li>\n");
        }
        body.append("</ul>\n");
        OperatorPage.close(body);
        return body.toString();
    }
}
