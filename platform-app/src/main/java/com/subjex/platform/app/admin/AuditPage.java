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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.Instant;

/**
 * AuditPage — 审计记录页：一页只读 HTML，人能看懂谁在什么时候对什么做了什么。
 * <p>
 * One sentence says what the page is. Each row is the time in UTC, the actor's login (or identity id),
 * the action in plain words, its target, and the outcome. Newest first, at most {@link #ROW_LIMIT} rows.
 * OBS-1: optional query filters (tenantId/actor/action/from/to) shared with the JSON twin.
 * 开头一句话说明这页是什么。OBS-1：可选筛选参数与 JSON 孪生共用。
 */
@RestController
public class AuditPage {

    /** Browser path — 浏览器路径。 */
    public static final String PATH = "/audit";

    /** Rows shown and returned — 展示和返回的行数上限。 */
    public static final int ROW_LIMIT = 200;

    /** UTC time format shared with the JSON twin — 与 JSON 孪生接口共用的 UTC 时间格式。 */
    static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /** Plain words for known actions (incl. identity OBS-1) — 已知动作白话（含身份事件）。 */
    static final Map<String, String[]> ACTION_WORDS = Map.ofEntries(
            Map.entry("config.override", new String[] {"覆盖配置", "override config"}),
            Map.entry("registry.register", new String[] {"登记服务", "register service"}),
            Map.entry("submit-task", new String[] {"提交任务", "submit task"}),
            Map.entry("declaration.migrate.enqueue", new String[] {"迁移入队", "migration enqueue"}),
            Map.entry("declaration.migrate.review", new String[] {"迁移审阅", "migration review"}),
            Map.entry("declaration.migrate.apply", new String[] {"迁移执行", "migration apply"}),
            Map.entry("declaration.migrate.cancel", new String[] {"迁移取消", "migration cancel"}),
            Map.entry("capability.ai.preview", new String[] {"AI 预览", "AI preview"}),
            Map.entry("capability.ai.confirm", new String[] {"AI 写回确认", "AI write confirm"}),
            Map.entry("operator.auth.login", new String[] {"口令登录", "password login"}),
            Map.entry("operator.auth.login.oidc", new String[] {"OIDC 登录", "OIDC login"}),
            Map.entry("operator.auth.login.mfa-challenge", new String[] {"MFA 挑战", "MFA challenge"}),
            Map.entry("operator.idp.bind", new String[] {"绑定 IdP", "bind IdP"}),
            Map.entry("operator.idp.unlink", new String[] {"解绑 IdP", "unlink IdP"}),
            Map.entry("operator.idp.self-link", new String[] {"自助绑定 IdP", "self-link IdP"}),
            Map.entry("operator.idp.self-unlink", new String[] {"自助解绑 IdP", "self-unlink IdP"}),
            Map.entry("oauth.client.create", new String[] {"创建 OAuth 客户端", "create OAuth client"}),
            Map.entry("oauth.client.rotate-secret", new String[] {"轮换客户端密钥", "rotate client secret"}),
            Map.entry("oauth.consent.approve", new String[] {"批准同意", "approve consent"}),
            Map.entry("oauth.consent.deny", new String[] {"拒绝同意", "deny consent"}),
            Map.entry("oauth.consent.revoke", new String[] {"吊销同意", "revoke consent"}),
            Map.entry("oauth.token.client_credentials", new String[] {"客户端凭证发令", "client_credentials token"}),
            Map.entry("oauth.token.authorization_code", new String[] {"授权码发令", "authorization_code token"}),
            Map.entry("tenant.quota", new String[] {"租户配额拒绝", "tenant quota refused"}),
            Map.entry("tenant.quota.override", new String[] {"租户配额覆盖", "tenant quota override"}),
            Map.entry("tenant.export", new String[] {"租户导出", "tenant export"}),
            Map.entry("tenant.erase.challenge", new String[] {"租户擦除挑战", "tenant erase challenge"}),
            Map.entry("tenant.erase", new String[] {"租户硬删除", "tenant hard erase"}));

    /** Plain words for outcomes — 结果的白话。 */
    static final Map<String, String[]> OUTCOME_WORDS = Map.of(
            "ALLOWED", new String[] {"放行", "allowed"},
            "REFUSED", new String[] {"拒绝", "refused"},
            "FAILED", new String[] {"失败", "failed"});

    private final JdbcAdminReader adminReader;

    public AuditPage(JdbcAdminReader adminReader) {
        this.adminReader = adminReader;
    }

    @GetMapping(path = PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "actor", required = false) String actorLogin,
            @RequestParam(value = "action", required = false) String actionName,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to) {
        Locale locale = OperatorPage.locale(request);
        AuditQuery query = new AuditQuery(tenantId, actorLogin, actionName, from, to, ROW_LIMIT);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(html(
                        adminReader.auditEntries(query),
                        query,
                        OperatorPage.title(PageTitleCatalog.AUDIT, locale),
                        OperatorPage.skin(request),
                        locale));
    }

    static String html(List<AuditRow> rows) {
        return html(rows, AuditQuery.unfiltered(ROW_LIMIT), "审计记录", NamedSkin.PLAIN, PageLanguage.CHINESE);
    }

    static String html(List<AuditRow> rows, AuditQuery query, String title, NamedSkin skin, Locale locale) {
        StringBuilder body = new StringBuilder();
        OperatorPage.open(body, title, locale, skin);
        body.append("""
                <p class="intro">这是一份只读的审计记录，最新的在前：谁、在什么时候、对什么、做了什么、结果如何。
                <span class="en">This is a read-only audit list, newest first: who did what to which target, when, and how it ended.</span></p>
                """);
        body.append(filterForm(query));
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
                String actor = actor(row);
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


    /** Minimal filter form for OBS-1 — OBS-1 极简筛选表单。 */
    private static String filterForm(AuditQuery query) {
        AuditQuery q = query == null ? AuditQuery.unfiltered(ROW_LIMIT) : query;
        return """
                <form class="audit-filters" method="get" action="/audit" style="margin:1em 0;display:flex;flex-wrap:wrap;gap:0.5em;align-items:end">
                <label>租户<span class="en">tenant</span><br/><input name="tenantId" value="%s"/></label>
                <label>操作者<span class="en">actor</span><br/><input name="actor" value="%s"/></label>
                <label>动作<span class="en">action</span><br/><input name="action" value="%s" placeholder="oauth.consent"/></label>
                <label>起<span class="en">from</span><br/><input name="from" value="%s" placeholder="2026-10-15T00:00:00Z"/></label>
                <label>止<span class="en">to</span><br/><input name="to" value="%s" placeholder="2026-10-16T00:00:00Z"/></label>
                <button type="submit">筛选<span class="en">filter</span></button>
                </form>
                """
                .formatted(
                        escAttr(q.tenantId()),
                        escAttr(q.actorLogin()),
                        escAttr(q.actionName()),
                        escAttr(q.from() == null ? null : q.from().toString()),
                        escAttr(q.to() == null ? null : q.to().toString()));
    }

    private static String escAttr(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return OperatorPage.escape(value);
    }

    /**
     * Actor shown for a row: login name, else identity id — 一行显示的操作者：登录名，没有就用身份标识。
     */
    static String actor(AuditRow row) {
        return row.actorLogin() != null ? row.actorLogin() : row.actorIdentityId();
    }

    /**
     * Plain zh/en pair for a known name; an unknown name stands for itself in both — 已知名字的中英白话；未知名字两边都是它自己。
     */
    static String[] plainPair(Map<String, String[]> known, String name) {
        if (name == null) {
            return new String[] {"", ""};
        }
        String[] pair = known.get(name);
        return pair == null ? new String[] {name, name} : pair.clone();
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
