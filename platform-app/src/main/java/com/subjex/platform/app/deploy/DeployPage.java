package com.subjex.platform.app.deploy;

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
 * DeployPage — 清单页：一页只读 HTML，人能看懂探针路径和内存上限。
 * <p>
 * One sentence says these manifests are not applied to a cluster. Each row is a workload
 * name, the image string, the liveness path, the readiness path, and the memory limit.
 * There is no cluster status and no apply button.
 * 开头一句话说明这些清单没有应用到任何集群。每一行是工作负载名、镜像字符串、存活路径、就绪路径和内存上限。
 * 没有集群状态，也没有应用按钮。
 */
@RestController
public class DeployPage {

    /** Browser path — 浏览器路径。 */
    public static final String PATH = "/deploy";

    /** The only sentence on the page, in Chinese — 这一页唯一的说明句。 */
    static final String NOT_APPLIED_ZH = "这些清单没有应用到任何集群。";

    /** The only sentence on the page, in English. */
    static final String NOT_APPLIED_EN = "These manifests are not applied to a cluster.";

    private final ManifestCatalog catalog;

    public DeployPage(ManifestCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping(path = PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(HttpServletRequest request) {
        Locale locale = OperatorPage.locale(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html(catalog.list(), OperatorPage.title(PageTitleCatalog.DEPLOY, locale), OperatorPage.skin(request), locale));
    }

    static String html(List<WorkloadManifest> workloads) {
        return html(workloads, "部署清单", NamedSkin.PLAIN, PageLanguage.CHINESE);
    }

    /**
     * Same manifest list, with the request language and skin.
     * 同一份清单，带上这次请求的语言和外观。
     */
    static String html(List<WorkloadManifest> workloads, String title, NamedSkin skin, Locale locale) {
        StringBuilder body = new StringBuilder();
        OperatorPage.open(body, title, locale, skin);
        body.append("<p class=\"intro\">").append(NOT_APPLIED_ZH)
                .append("<span class=\"en\">").append(NOT_APPLIED_EN).append("</span></p>\n");
        body.append("""
                <table>
                <thead>
                <tr>
                <th>工作负载<span class="en">workload</span></th>
                <th>镜像<span class="en">image</span></th>
                <th>探针路径<span class="en">probe paths</span></th>
                <th>内存上限<span class="en">memory limit</span></th>
                </tr>
                </thead>
                <tbody>
                """);
        for (WorkloadManifest workload : workloads) {
            body.append("<tr><td>")
                    .append(escape(workload.name()))
                    .append("</td><td><code>")
                    .append(escape(workload.image()))
                    .append("</code>占位，未构建镜像<span class=\"en\">placeholder, image is not built</span></td><td>存活 ")
                    .append(escape(workload.livenessPath()))
                    .append("<span class=\"en\">liveness ")
                    .append(escape(workload.livenessPath()))
                    .append("</span>就绪 ")
                    .append(escape(workload.readinessPath()))
                    .append("<span class=\"en\">readiness ")
                    .append(escape(workload.readinessPath()))
                    .append("</span></td><td>")
                    .append(escape(workload.memoryLimitInChinese()))
                    .append("<span class=\"en\">")
                    .append(escape(workload.memoryLimitInEnglish()))
                    .append("</span></td></tr>\n");
        }
        body.append("""
                </tbody>
                </table>
                """);
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
