package com.subjex.platform.app.discovery;

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
    public ResponseEntity<String> page(HttpServletRequest request) {
        Locale locale = OperatorPage.locale(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html(catalog.list(), OperatorPage.title(PageTitleCatalog.SERVICES, locale), OperatorPage.skin(request), locale));
    }

    static String html(List<ListedService> services) {
        return html(services, "服务名单", NamedSkin.PLAIN, PageLanguage.CHINESE);
    }

    /**
     * Same list, with the request language and skin.
     * 同一份名单，带上这次请求的语言和外观。
     */
    static String html(List<ListedService> services, String title, NamedSkin skin, Locale locale) {
        StringBuilder body = new StringBuilder();
        OperatorPage.open(body, title, locale, skin);
        body.append("""
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
