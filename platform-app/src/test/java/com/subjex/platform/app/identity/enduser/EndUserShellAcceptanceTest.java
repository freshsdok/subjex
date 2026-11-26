package com.subjex.platform.app.identity.enduser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.inbox.EndUserShellInboxEndpoint;
import com.subjex.platform.app.inbox.JdbcInboxPort;
import com.subjex.platform.app.entity.LocalDirChunkedBlobUploadStore;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.questionnaire.EndUserShellQuestionnaireEndpoint;
import com.subjex.platform.app.questionnaire.JdbcQuestionnairePort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.JdbcObjectGrantStore;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.app.storage.LocalDownloadSignature;
import com.subjex.platform.app.storage.LocalHmacDownloadIssuer;
import com.subjex.platform.app.storage.ObjectAccessAudit;
import com.subjex.platform.app.storage.ObjectAccessAuthorizer;
import com.subjex.platform.app.storage.ObjectDownloadDeniedException;
import com.subjex.platform.app.storage.ObjectDownloadEndpoint;
import com.subjex.platform.app.storage.ObjectDownloadLinkService;
import com.subjex.platform.app.storage.ObjectDownloadProperties;
import com.subjex.platform.app.storage.ObjectUploadLimits;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxRecipient;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.inbox.InboxSendRequest;
import com.subjex.platform.contract.inbox.InboxSendResult;
import com.subjex.platform.contract.inbox.InboxSeverity;
import com.subjex.platform.contract.questionnaire.QuestionType;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectMode;
import com.subjex.platform.contract.questionnaire.QuestionnaireDefinition;
import com.subjex.platform.contract.questionnaire.QuestionnaireItem;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * EndUserShellAcceptanceTest — purpose/gate WP7-4: thin formal acceptance for the end-user shell.
 * An end user in a business tenant can update profile, read own inbox, submit AUTH_END_USER once,
 * and upload/list/download an owned file. Operator tokens and other users are denied; reserved
 * {@code platform} / missing tenant fail closed. Also guards the uni-app client against leftover
 * operator routes and domain pages.
 * <p>
 * 目的/门禁 WP7-4：终端壳正式薄验收。业务租户中的终端用户可改资料、读本人信件、提交认证问卷一次、
 * 上传并下载本人文件。操作员与他人拒绝；保留租户/缺租户失败关闭。并守卫 uni-app 不再调用操作员路由、无域页面。
 */
class EndUserShellAcceptanceTest {

    private static final String TENANT_A = "tenant-wp74-a";
    private static final String TENANT_B = "tenant-wp74-b";
    private static final Instant T0 = Instant.parse("2026-11-20T09:00:00Z");
    private static final byte[] FILE_BYTES = "wp7-4-file".getBytes(StandardCharsets.UTF_8);

    private JdbcInboxPort inbox;
    private MockMvc mvc;
    private EndUserRecord alice;
    private EndUserRecord bob;
    private String aliceEntryId;
    private String publishedAuthId;
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path objectRoot;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_A,
                "Tenant A");
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_B,
                "Tenant B");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        JdbcEndUserAccountStore accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        inbox = new JdbcInboxPort(jdbc, tx);
        RateLimitPort alwaysPermit = (tenantId, actionName) -> true;
        JdbcQuestionnairePort questionnaires = new JdbcQuestionnairePort(jdbc, tx, clock, alwaysPermit);
        LocalDirectoryObjectStorage storage = new LocalDirectoryObjectStorage(objectRoot);
        EndUserShellService shell = new EndUserShellService(
                accounts, inbox, questionnaires, new JdbcAuditPort(jdbc), storage, clock);
        ObjectAccessAudit audit = new ObjectAccessAudit(new JdbcAuditPort(jdbc), clock);
        JdbcObjectGrantStore grants = new JdbcObjectGrantStore(jdbc, clock, audit);
        EndUserObjectService objects = new EndUserObjectService(
                accounts,
                storage,
                grants,
                ObjectUploadLimits.defaults(),
                audit,
                new LocalDirChunkedBlobUploadStore(objectRoot.resolve("chunks"), clock));
        LocalDownloadSignature signature = new LocalDownloadSignature("test-hmac-key");
        ObjectDownloadProperties properties = new ObjectDownloadProperties(
                ObjectDownloadProperties.DEFAULT_TTL,
                ObjectDownloadProperties.HARD_CAP,
                "http://127.0.0.1:8080",
                "test-hmac-key");
        ObjectDownloadLinkService downloads = new ObjectDownloadLinkService(
                new ObjectAccessAuthorizer(grants, jdbc, clock),
                new LocalHmacDownloadIssuer(signature, properties.publicBaseUrl(), clock),
                signature,
                storage,
                properties,
                clock,
                audit);
        mvc = MockMvcBuilders.standaloneSetup(
                        new EndUserProfileEndpoint(shell),
                        new EndUserShellInboxEndpoint(shell),
                        new EndUserShellQuestionnaireEndpoint(shell),
                        new EndUserObjectEndpoint(objects),
                        new ObjectDownloadEndpoint(downloads))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter())
                .build();

        alice = accounts.provision(TENANT_A, "Alice");
        bob = accounts.provision(TENANT_A, "Bob");
        accounts.provision(TENANT_B, "Carol");

        aliceEntryId = sendEndUserMail(TENANT_A, alice.subject().subjectId(), "给 Alice", "仅 Alice 可见");
        sendEndUserMail(TENANT_A, bob.subject().subjectId(), "给 Bob", "仅 Bob 可见");

        QuestionnaireDefinition published =
                questionnaires.create(TENANT_A, "终端调研", QuestionnaireCollectMode.AUTH_END_USER);
        questionnaires.replaceItems(
                TENANT_A,
                published.questionnaireId(),
                List.of(new QuestionnaireItem("i1", 1, QuestionType.TEXT_SHORT, "建议", true, null)));
        publishedAuthId = questionnaires.publish(TENANT_A, published.questionnaireId()).orElseThrow().questionnaireId();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void endUserCompletesProfileInboxQuestionnaireAndFileRoundTrip() throws Exception {
        mvc.perform(patch(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"Alice Zhang\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectName").value("Alice Zhang"));

        mvc.perform(get(EndUserShellInboxEndpoint.BASE + "/entries/" + aliceEntryId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("给 Alice"));

        String answers = "{\"answers\":[{\"itemId\":\"i1\",\"valueJson\":\"\\\"ok\\\"\"}]}";
        mvc.perform(post(EndUserShellQuestionnaireEndpoint.BASE + "/" + publishedAuthId + "/responses")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answers))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respondentSubjectId").value(alice.subject().subjectId()));
        mvc.perform(post(EndUserShellQuestionnaireEndpoint.BASE + "/" + publishedAuthId + "/responses")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(answers))
                .andExpect(status().isConflict());

        MvcResult uploaded = mvc.perform(multipart(EndUserObjectEndpoint.PATH)
                        .file(new MockMultipartFile("file", "note.txt", "text/plain", FILE_BYTES))
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andReturn();
        String objectKey = mapper.readTree(uploaded.getResponse().getContentAsString())
                .get("objectKey")
                .asText();
        mvc.perform(get(EndUserObjectEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects.length()").value(1))
                .andExpect(jsonPath("$.objects[0].objectKey").value(objectKey));
        MvcResult issued = mvc.perform(post(ObjectDownloadEndpoint.END_USER_ISSUE_PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"" + objectKey + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String url = mapper.readTree(issued.getResponse().getContentAsString()).get("url").asText();
        URI uri = URI.create(url);
        mvc.perform(get(uri.getRawPath() + "?" + uri.getRawQuery()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(FILE_BYTES));
    }

    @Test
    void operatorOtherUserPlatformAndMissingTenantAreRejected() throws Exception {
        OperatorPrincipal operator =
                new OperatorPrincipal("op-alice", "hash", "op-id", "op-sub", Set.of(), true);
        mvc.perform(get(EndUserProfileEndpoint.PATH)
                        .with(asPrincipal(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("end-user-authentication-required"));
        mvc.perform(get(EndUserShellInboxEndpoint.BASE + "/entries")
                        .with(asPrincipal(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());
        mvc.perform(get(EndUserObjectEndpoint.PATH)
                        .with(asPrincipal(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        mvc.perform(get(EndUserShellInboxEndpoint.BASE + "/entries/" + aliceEntryId)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());
        mvc.perform(get(EndUserObjectEndpoint.PATH)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects.length()").value(0));
        mvc.perform(post(ObjectDownloadEndpoint.END_USER_ISSUE_PATH)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"eu-missing\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ObjectDownloadDeniedException.REASON));

        mvc.perform(get(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, EndUserAccountStore.RESERVED_TENANT))
                .andExpect(status().isBadRequest());
        mvc.perform(get(EndUserProfileEndpoint.PATH).with(asEndUser(alice)))
                .andExpect(status().isForbidden());
    }

    @Test
    void uniAppClientDoesNotCallOperatorRoutesOrAddDomainPages() throws Exception {
        Path src = repoRoot().resolve("clients/uni-app/src");
        assertThat(src).isDirectory();
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path file : walk.filter(Files::isRegularFile).filter(EndUserShellAcceptanceTest::isTextSource).toList()) {
                String relative = src.relativize(file).toString().replace('\\', '/');
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                String code = stripComments(raw);
                if (code.contains("/api/v1/me/inbox")) {
                    hits.add(relative + " calls /api/v1/me/inbox");
                }
                if (code.contains("/api/v1/auth/login")) {
                    hits.add(relative + " calls /api/v1/auth/login");
                }
                int from = 0;
                while (true) {
                    int at = code.indexOf("/api/v1/questionnaires", from);
                    if (at < 0) {
                        break;
                    }
                    String rest = code.substring(at);
                    if (!rest.startsWith("/api/v1/questionnaires/public/")) {
                        hits.add(relative + " calls operator questionnaire route");
                    }
                    from = at + "/api/v1/questionnaires".length();
                }
            }
        }
        Path pages = src.resolve("pages");
        Path pagesJson = src.resolve("pages.json");
        String pagesJsonText = Files.readString(pagesJson, StandardCharsets.UTF_8);
        for (String term : domainTerms()) {
            if (containsIgnoreCase(pagesJsonText, term)) {
                hits.add("pages.json mentions domain term: " + term);
            }
        }
        try (Stream<Path> walk = Files.walk(pages)) {
            for (Path file : walk.toList()) {
                String relative = src.relativize(file).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                for (String term : domainTerms()) {
                    if (relative.contains(term.toLowerCase(Locale.ROOT))) {
                        hits.add("domain page path: " + relative);
                    }
                }
            }
        }
        assertThat(hits).as("uni-app client leftover operator routes or domain pages").isEmpty();
    }

    @Test
    void uniAppNavCoversEveryShellPageInPagesJson() throws Exception {
        Path root = repoRoot();
        String pagesJson = Files.readString(root.resolve("clients/uni-app/src/pages.json"), StandardCharsets.UTF_8);
        String yaml = Files.readString(
                root.resolve("platform-app/src/main/resources/ui-authz/uni-app-ui.yaml"), StandardCharsets.UTF_8);
        String home = Files.readString(root.resolve("clients/uni-app/src/pages/index/index.vue"), StandardCharsets.UTF_8);
        Matcher paths = Pattern.compile("\"path\"\\s*:\\s*\"(pages/[^\"]+)\"").matcher(pagesJson);
        List<String> pages = new ArrayList<>();
        while (paths.find()) {
            pages.add("/" + paths.group(1));
        }
        assertThat(pages).as("pages.json shell routes").isNotEmpty();
        for (String page : pages) {
            if ("/pages/questionnaire/fill".equals(page)) {
                assertThat(yaml).as("fill page is a Manifest page route, not a home menu").contains("route: " + page);
                continue;
            }
            assertThat(yaml).as("Manifest menu href for " + page).contains("href: " + page);
            assertThat(home).as("home fallback nav for " + page).contains(page);
        }

        JsonNode tabList = new ObjectMapper().readTree(pagesJson).path("tabBar").path("list");
        assertThat(tabList.size()).as("WeChat tabBar allows at most 5 items").isEqualTo(5);
        List<String> tabPaths = new ArrayList<>();
        Path staticRoot = root.resolve("clients/uni-app/src");
        for (JsonNode item : tabList) {
            String pagePath = item.path("pagePath").asText();
            assertThat(pagePath).isNotBlank();
            tabPaths.add("/" + pagePath);
            assertThat(staticRoot.resolve(item.path("iconPath").asText()))
                    .as("tab icon for " + pagePath)
                    .exists()
                    .isRegularFile();
            assertThat(staticRoot.resolve(item.path("selectedIconPath").asText()))
                    .as("selected tab icon for " + pagePath)
                    .exists()
                    .isRegularFile();
        }
        assertThat(tabPaths)
                .containsExactly(
                        "/pages/index/index",
                        "/pages/inbox/inbox",
                        "/pages/questionnaire/list",
                        "/pages/files/files",
                        "/pages/profile/profile");
        // Tab pages must be listed first so H5 and the WeChat mini-program can switchTab.
        // 标签页必须排在 pages 前列，H5 与微信小程序才能用 switchTab。
        assertThat(pages.subList(0, tabPaths.size())).containsExactlyElementsOf(tabPaths);
    }

    private String sendEndUserMail(String tenantId, String subjectId, String title, String body) {
        InboxSendResult sent = inbox.send(new InboxSendRequest(
                tenantId,
                InboxChannel.SYSTEM,
                title,
                body,
                InboxSeverity.INFO,
                "system",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new InboxRecipient(InboxRecipientRealm.END_USER, subjectId)),
                T0));
        return sent.entries().get(0).entryId();
    }

    private RequestPostProcessor asEndUser(EndUserRecord record) {
        return asPrincipal(new EndUserPrincipal(
                record.account().accountId(),
                record.subject().subjectId(),
                record.account().loginName(),
                true));
    }

    private static RequestPostProcessor asPrincipal(Object principal) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("platform-app"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate repo root from " + System.getProperty("user.dir"));
    }

    /** Strip //, block, and HTML comments before scanning call sites — 扫描调用点前去掉注释. */
    private static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        String noHtml = noBlock.replaceAll("(?s)<!--.*?-->", " ");
        StringBuilder out = new StringBuilder(noHtml.length());
        for (String line : noHtml.split("\\R", -1)) {
            int cut = line.indexOf("//");
            out.append(cut >= 0 ? line.substring(0, cut) : line).append('\n');
        }
        return out.toString();
    }

    private static String[] domainTerms() {
        return new String[] {
            "medical",
            "hospital",
            "clinic",
            "appointment",
            "payment",
            "visa",
            "insurance",
            "医疗",
            "医院",
            "预约",
            "支付",
            "签证",
            "保险"
        };
    }

    private static boolean isTextSource(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".vue")
                || name.endsWith(".ts")
                || name.endsWith(".js")
                || name.endsWith(".json")
                || name.endsWith(".css")
                || name.endsWith(".scss")
                || name.endsWith(".md")
                || name.endsWith(".html")
                || name.endsWith(".txt");
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
