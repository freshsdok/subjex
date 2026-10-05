package com.subjex.platform.app.view;

import com.subjex.language.PageLanguage;
import com.subjex.skin.NamedSkin;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;

/**
 * OperatorPage — 操作页外壳：标题来自 MessageSource，外观来自查询或 cookie。
 * <p>
 * Spring looks up a bean named {@code messageSource}. This page uses that same bundle.
 * The locale is not saved. The skin cookie is written only by the skin page.
 * Spring 会找一个名叫 {@code messageSource} 的 bean。这一页用的是同一份目录。
 * 语言不保存。外观 cookie 只由外观页写入。
 */
public final class OperatorPage {

    /** Saved skin name — 保存的外观名。 */
    public static final String SKIN_COOKIE = "skin";

    private static final MessageSource MESSAGES = buildMessages();

    private OperatorPage() {}

    /**
     * The catalog Spring resolves by locale — Spring 按语言解析的那份目录。
     */
    public static MessageSource messages() {
        return MESSAGES;
    }

    /**
     * Language for this request — 这一次请求的语言。
     */
    public static Locale locale(HttpServletRequest request) {
        return PageLanguage.resolve(request.getParameter("lang"), request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
    }

    /**
     * Skin for this request — 这一次请求的外观。
     */
    public static NamedSkin skin(HttpServletRequest request) {
        return NamedSkin.choose(request.getParameter("skin"), savedSkin(request));
    }

    /**
     * Phrase for the current language — 当前语言的那句话。
     */
    public static String title(String code, Locale locale) {
        return MESSAGES.getMessage(code, null, locale);
    }

    /**
     * html lang attribute — html 的 lang。
     */
    public static String htmlLang(Locale locale) {
        if (locale != null && PageLanguage.ENGLISH.getLanguage().equals(locale.getLanguage())) {
            return "en";
        }
        return "zh-Hans";
    }

    /**
     * Open a page in the selected skin — 用选中的外观打开一页。
     */
    public static void open(StringBuilder body, String title, Locale locale, NamedSkin skin) {
        body.append("<!DOCTYPE html>\n<html lang=\"")
                .append(htmlLang(locale))
                .append("\" data-skin=\"")
                .append(skin.token())
                .append("\">\n<head>\n<meta charset=\"utf-8\">\n<title>")
                .append(escape(title))
                .append("</title>\n<style>\n")
                .append(NamedSkin.styleSheet())
                .append("</style>\n</head>\n<body>\n");
    }

    /** Close the page — 关上这一页。 */
    public static void close(StringBuilder body) {
        body.append("</body>\n</html>\n");
    }

    /** Escape text that a person typed into a page — 转义写进页面的文字。 */
    public static String escape(String value) {
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

    private static String savedSkin(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SKIN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static MessageSource buildMessages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("pagephrases");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setDefaultLocale(PageLanguage.CHINESE);
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
