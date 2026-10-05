package com.subjex.language;

import java.util.List;
import java.util.Locale;

/**
 * PageLanguage — 页面语言：一次请求选出中文或英文。
 * <p>
 * {@code lang=zh} or {@code lang=en} wins. Otherwise the Accept-Language header is matched
 * against those two languages. Anything else is Chinese. The choice is not stored.
 * {@code lang=zh} 或 {@code lang=en} 优先。否则用 Accept-Language 在这两种语言里匹配。
 * 其余情况是中文。这个选择不保存。
 */
public final class PageLanguage {

    /** Chinese, the default — 中文，默认语言。 */
    public static final Locale CHINESE = Locale.forLanguageTag("zh");

    /** English — 英文。 */
    public static final Locale ENGLISH = Locale.ENGLISH;

    private static final List<Locale> SUPPORTED = List.of(CHINESE, ENGLISH);

    private PageLanguage() {}

    /**
     * Resolve one request. A blank or unknown query does not hide the header.
     * 解析一次请求。空的或认不出的查询不会挡住请求头。
     */
    public static Locale resolve(String langQuery, String acceptLanguage) {
        if ("zh".equals(langQuery)) {
            return CHINESE;
        }
        if ("en".equals(langQuery)) {
            return ENGLISH;
        }
        Locale fromHeader = matchHeader(acceptLanguage);
        return fromHeader == null ? CHINESE : fromHeader;
    }

    /**
     * Plain name of the current language — 当前语言的人话名字。
     */
    public static String name(Locale locale) {
        if (locale != null && ENGLISH.getLanguage().equals(locale.getLanguage())) {
            return "English";
        }
        return "中文";
    }

    private static Locale matchHeader(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return null;
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
            return Locale.lookup(ranges, SUPPORTED);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
