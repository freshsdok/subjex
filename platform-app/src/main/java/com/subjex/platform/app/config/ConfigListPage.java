package com.subjex.platform.app.config;

import com.subjex.platform.contract.config.ConfigEntry;
import java.util.List;
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
 * ConfigListPage — 配置名单页：一页只读 HTML，人能看懂键、生效值，以及它从哪一层来。
 * <p>
 * One sentence says what the page is. Each row is a key, the winning value, and local file or memory override.
 * There is no editor, namespace tree, or historical version list.
 * 开头一句话说明这页是什么。每一行是键、生效值，以及本地文件或内存覆盖。
 * 没有编辑器、命名空间树，也没有历史版本列表。
 */
@RestController
public class ConfigListPage {

    /** Browser path — 浏览器路径。 */
    public static final String PATH = "/config";

    private final ConfigCatalog catalog;

    public ConfigListPage(ConfigCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping(path = PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(HttpServletRequest request) {
        Locale locale = OperatorPage.locale(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html(catalog.list(), OperatorPage.title(PageTitleCatalog.CONFIG, locale), OperatorPage.skin(request), locale));
    }

    static String html(List<ConfigEntry> entries) {
        return html(entries, "配置名单", NamedSkin.PLAIN, PageLanguage.CHINESE);
    }

    /**
     * Same list, with the request language and skin.
     * 同一份名单，带上这次请求的语言和外观。
     */
    static String html(List<ConfigEntry> entries, String title, NamedSkin skin, Locale locale) {
        StringBuilder body = new StringBuilder();
        OperatorPage.open(body, title, locale, skin);
        body.append("""
                <p class="intro">这是一份只读的配置名单，只列出键、生效值，以及它来自本地文件还是内存覆盖。
                <span class="en">This is a read-only config list: a key, the effective value, and whether it came from a local file or a memory override.</span></p>
                """);
        if (entries.isEmpty()) {
            body.append("""
                    <p>还没有可展示的配置。<span class="en">No config entry is listed.</span></p>
                    """);
        } else {
            body.append("""
                    <table>
                    <thead>
                    <tr>
                    <th>键<span class="en">key</span></th>
                    <th>生效值<span class="en">effective value</span></th>
                    <th>来源<span class="en">source</span></th>
                    </tr>
                    </thead>
                    <tbody>
                    """);
            for (ConfigEntry entry : entries) {
                body.append("<tr><td><code>")
                        .append(escape(entry.key()))
                        .append("</code></td><td>")
                        .append(escape(entry.value()))
                        .append("</td><td>")
                        .append(escape(entry.origin().chinese()))
                        .append("<span class=\"en\">")
                        .append(escape(entry.origin().english()))
                        .append("</span></td></tr>\n");
            }
            body.append("""
                    </tbody>
                    </table>
                    """);
        }
        OperatorPage.close(body);
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
}
