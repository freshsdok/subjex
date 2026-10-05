package com.subjex.platform.app.config;

import com.subjex.platform.contract.config.ConfigEntry;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    public ResponseEntity<String> page() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html(catalog.list()));
    }

    static String html(List<ConfigEntry> entries) {
        StringBuilder body = new StringBuilder();
        body.append("""
                <!DOCTYPE html>
                <html lang="zh-Hans">
                <head>
                <meta charset="utf-8">
                <title>配置名单</title>
                <style>
                  body { font-family: system-ui, sans-serif; margin: 2rem; max-width: 48rem; color: #1c1c1c; line-height: 1.4; }
                  table { border-collapse: collapse; width: 100%; margin-top: 1rem; }
                  th, td { text-align: left; padding: 0.6rem 0.75rem; border-bottom: 1px solid #ddd; vertical-align: top; }
                  .en { display: block; color: #555; font-size: 0.85rem; font-weight: normal; }
                  .intro { margin: 0; }
                  code { font-size: 0.95rem; }
                </style>
                </head>
                <body>
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
        body.append("""
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
}
