package com.subjex.platform.app.admin;

import com.subjex.language.PageLanguage;
import com.subjex.language.PageTitleCatalog;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.view.OperatorPage;
import com.subjex.skin.NamedSkin;
import jakarta.servlet.http.HttpServletRequest;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AuditPage — 审计记录页：一页只读 HTML，人能看懂谁在什么时候对什么做了什么。
 * <p>
 * One sentence says what the page is. Each row is the time in UTC, the actor's login (or identity id),
 * the action in plain words, its target, and the outcome. Newest first, at most {@link #ROW_LIMIT} rows.
 * There is no filter, export, or delete button.
 * 开头一句话说明这页是什么。每一行是 UTC 时间、操作者登录名（或身份标识）、白话动作、对象和结果。
 * 最新的在前，最多 {@link #ROW_LIMIT} 行。没有筛选、导出或删除按钮。
 */
@RestController
public class AuditPage {

    /** Browser path — 浏览器路径。 */
    public static final String PATH = "/audit";

    /** Rows shown and returned — 展示和返回的行数上限。 */
    public static final int ROW_LIMIT = 200;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /** Plain words for known actions — 已知动作的白话。 */
    private static final Map<String, String[]> ACTION_WORDS = Map.of(
            "config.override", new String[] {"覆盖配置", "override config"},
            "registry.register", new String[] {"登记服务", "register service"},
            "submit-task", new String[] {"提交任务", "submit task"});

    /** Plain words for outcomes — 结果的白话。 */
    private static final Map<String, String[]> OUTCOME_WORDS = Map.of(
            "ALLOWED", new String[] {"放行", "allowed"},
            "REFUSED", new String[] {"拒绝", "refused"},
            "FAILED", new String[] {"失败", "failed"});

    private final JdbcAdminReader adminReader;

    public AuditPage(JdbcAdminReader adminReader) {
        this.adminReader = adminReader;
    }

    @GetMapping(path = PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(HttpServletRequest request) {
        Locale locale = OperatorPage.locale(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html(
                        adminReader.auditEntries(ROW_LIMIT),
                        OperatorPage.title(PageTitleCatalog.AUDIT, locale),
                        OperatorPage.skin(request),
                        locale));
    }

    static String html(List<AuditRow> rows) {
        return html(rows, "审计记录", NamedSkin.PLAIN, PageLanguage.CHINESE);
    }

    static String html(List<AuditRow> rows, String title, NamedSkin skin, Locale locale) {
        StringBuilder body = new StringBuilder();
        OperatorPage.open(body, title, locale, skin);
        body.append("""
                <p class="intro">这是一份只读的审计记录，最新的在前：谁、在什么时候、对什么、做了什么、结果如何。
                <span class="en">This is a read-only audit list, newest first: who did what to which target, when, and how it ended.</span></p>
                """);
        if (rows.isEmpty()) {
            body.append("""
                    <p>还没有审计条目。<span class="en">No audit entry is recorded.</span></p>
                    """);
        } else {
            body.append("""
                    <table>
                    <thead>
                    <tr>
                    <th>时间<span class="en">time</span></th>
                    <th>操作者<span class="en">actor</span></th>
                    <th>动作<span class="en">action</span></th>
                    <th>对象<span class="en">target</span></th>
                    <th>结果<span class="en">outcome</span></th>
                    </tr>
                    </thead>
                    <tbody>
                    """);
            for (AuditRow row : rows) {
                String actor = row.actorLogin() != null ? row.actorLogin() : row.actorIdentityId();
                body.append("<tr><td>")
                        .append(row.occurredAt() == null ? "" : TIME.format(row.occurredAt()))
                        .append("</td><td>")
                        .append(OperatorPage.escape(actor == null ? "" : actor))
                        .append("</td><td>")
                        .append(words(ACTION_WORDS, row.actionName()))
                        .append("</td><td>")
                        .append(OperatorPage.escape(row.actionTarget() == null ? "" : row.actionTarget()))
                        .append("</td><td>")
                        .append(words(OUTCOME_WORDS, row.outcome()))
                        .append("</td></tr>\n");
            }
            body.append("""
                    </tbody>
                    </table>
                    """);
        }
        OperatorPage.close(body);
        return body.toString();
    }

    private static String words(Map<String, String[]> known, String name) {
        if (name == null) {
            return "";
        }
        String[] pair = known.get(name);
        if (pair == null) {
            return OperatorPage.escape(name);
        }
        return pair[0] + "<span class=\"en\">" + pair[1] + "</span>";
    }
}
