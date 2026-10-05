package com.subjex.platform.app.discovery;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ServiceListPage — 服务名单页：一页只读 HTML，人能看懂谁在哪个地址。
 * <p>
 * One sentence says what the page is. Each row is a name, an address, and the word up or unknown.
 * There is no chart, weight, metadata editor, or online/offline switch.
 * 开头一句话说明这页是什么。每一行是名字、地址，以及 up 或 unknown。
 * 没有图，没有权重，没有元数据编辑，也没有上下线开关。
 */
@RestController
public class ServiceListPage {

    /** Browser path — 浏览器路径。 */
    public static final String PATH = "/services";

    private final ServiceCatalog catalog;

    public ServiceListPage(ServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping(path = PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html(catalog.list()));
    }

    static String html(List<ListedService> services) {
        StringBuilder body = new StringBuilder();
        body.append("""
                <!DOCTYPE html>
                <html lang="zh-Hans">
                <head>
                <meta charset="utf-8">
                <title>服务名单</title>
                <style>
                  body { font-family: system-ui, sans-serif; margin: 2rem; max-width: 40rem; color: #1c1c1c; line-height: 1.4; }
                  table { border-collapse: collapse; width: 100%; margin-top: 1rem; }
                  th, td { text-align: left; padding: 0.6rem 0.75rem; border-bottom: 1px solid #ddd; vertical-align: top; }
                  .en { display: block; color: #555; font-size: 0.85rem; font-weight: normal; }
                  .intro { margin: 0; }
                </style>
                </head>
                <body>
                <p class="intro">这是一份只读的服务名单，只列出名字、地址，以及此刻能不能连上。
                <span class="en">This is a read-only service list: a name, an address, and whether it answers right now.</span></p>
                """);
        if (services.isEmpty()) {
            body.append("""
                    <p>还没有登记的服务。<span class="en">No service is registered.</span></p>
                    """);
        } else {
            body.append("""
                    <table>
                    <thead>
                    <tr>
                    <th>服务名<span class="en">service name</span></th>
                    <th>地址<span class="en">address</span></th>
                    <th>状态<span class="en">status</span></th>
                    </tr>
                    </thead>
                    <tbody>
                    """);
            for (ListedService service : services) {
                String word = AddressProbe.UP.equals(service.status()) ? AddressProbe.UP : AddressProbe.UNKNOWN;
                String plain = AddressProbe.UP.equals(word) ? "可用" : "未知";
                body.append("<tr><td>")
                        .append(escape(service.serviceName()))
                        .append("</td><td>")
                        .append(escape(service.host()))
                        .append(':')
                        .append(service.port())
                        .append("</td><td>")
                        .append(plain)
                        .append("<span class=\"en\">")
                        .append(word)
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
