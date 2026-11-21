package com.subjex.platform.app.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.consent.JdbcConsentPort;
import com.subjex.platform.app.exporthook.ExportApprovalGate;
import com.subjex.platform.app.exporthook.ExportExpiryService;
import com.subjex.platform.app.exporthook.ExportGenerationService;
import com.subjex.platform.app.exporthook.ExportRequestService;
import com.subjex.platform.app.exporthook.GenericSubjectRecordExporter;
import com.subjex.platform.app.exporthook.JdbcExportRequestStore;
import com.subjex.platform.app.exporthook.OperatorExportEndpoint;
import com.subjex.platform.app.identity.enduser.EndUserExportEndpoint;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserRecord;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.inbox.JdbcInboxPort;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.questionnaire.JdbcQuestionnairePort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.PolicyEngineFactory;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.JdbcObjectGrantStore;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.app.storage.LocalDownloadSignature;
import com.subjex.platform.app.storage.LocalHmacDownloadIssuer;
import com.subjex.platform.app.storage.ObjectAccessAudit;
import com.subjex.platform.app.storage.ObjectAccessAuthorizer;
import com.subjex.platform.app.storage.ObjectDownloadEndpoint;
import com.subjex.platform.app.storage.ObjectDownloadLinkService;
import com.subjex.platform.app.storage.ObjectDownloadProperties;
import com.subjex.platform.app.storage.ObjectGrantStore;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.workflow.JdbcWorkflowStore;
import com.subjex.platform.app.workflow.WorkflowCatalog;
import com.subjex.platform.app.workflow.WorkflowExportHook;
import com.subjex.platform.app.workflow.WorkflowService;
import com.subjex.platform.contract.consent.ConsentPort;
import com.subjex.platform.contract.consent.ConsentRequiredException;
import com.subjex.platform.contract.exporthook.ExportExpiredException;
import com.subjex.platform.contract.exporthook.ExportNotApprovedException;
import com.subjex.platform.contract.exporthook.ExportSelfApprovalException;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.inbox.InboxRecipient;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.inbox.InboxSendRequest;
import com.subjex.platform.contract.inbox.InboxSeverity;
import com.subjex.platform.contract.questionnaire.QuestionType;
import com.subjex.platform.contract.questionnaire.QuestionnaireAnswer;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectMode;
import com.subjex.platform.contract.questionnaire.QuestionnaireDefinition;
import com.subjex.platform.contract.questionnaire.QuestionnaireItem;
import com.subjex.platform.contract.questionnaire.QuestionnairePort;
import com.subjex.platform.contract.questionnaire.QuestionnaireRespondentRealm;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * ConsentExportAcceptanceTest — formal WP8-4 umbrella for ADR 0020 / canvas WP8 close.
 * Does not repeat the focused WP8-1..WP8-3 suites; locks consent gate, export flow, isolation, and kernel guards.
 * WP8-4 正式验收伞测试：锁定同意门禁、导出全流程、隔离与内核护栏，不重复聚焦套件。
 */
class ConsentExportAcceptanceTest {

    private static final String TENANT_A = "tenant-wp84-a";
    private static final String TENANT_B = "tenant-wp84-b";
    private static final String PURPOSE = "platform.notice.v1";
    private static final String VERSION = "text-2026-11";
    private static final String ALICE_INBOX = "alice-inbox-wp84";
    private static final String BOB_INBOX = "bob-inbox-wp84";
    private static final String ALICE_ANSWER = "alice-answer-wp84";
    private static final String BOB_ANSWER = "bob-answer-wp84";

    private JdbcTemplate jdbc;
    private ConsentPort consents;
    private MockMvc mvc;
    private EndUserRecord alice;
    private EndUserRecord bob;
    private EndUserRecord carol;
    private OperatorPrincipal requester;
    private OperatorPrincipal approver;
    private OperatorPrincipal both;
    private final ObjectMapper mapper = new ObjectMapper();
    private Clock clock;

    @TempDir
    Path objectRoot;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_A,
                "Tenant A");
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_B,
                "Tenant B");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        clock = Clock.system(ZoneOffset.UTC);
        JdbcEndUserAccountStore accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        JdbcOperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        WorkflowService workflows = new WorkflowService(
                new JdbcWorkflowStore(jdbc, tx),
                tenantAccess,
                new CapabilityCatalog(),
                new OperatorActionAudit(new JdbcAuditPort(jdbc), clock),
                clock,
                null,
                null,
                null,
                new WorkflowCatalog());
        JdbcExportRequestStore store = new JdbcExportRequestStore(jdbc);
        JdbcAuditPort audit = new JdbcAuditPort(jdbc);
        ExportApprovalGate gate = new ExportApprovalGate(store, audit, clock);
        ExportRequestService service = new ExportRequestService(
                jdbc,
                store,
                workflows,
                accounts,
                tenantAccess,
                PolicyEngineFactory.create("sql", new DenyWhenTenantMissing(), tenantAccess),
                audit,
                clock);
        ObjectAccessAudit objectAudit = new ObjectAccessAudit(audit, clock);
        ObjectGrantStore objects = new JdbcObjectGrantStore(jdbc, clock, objectAudit);
        ObjectStorage storage = new LocalDirectoryObjectStorage(objectRoot);
        consents = new JdbcConsentPort(jdbc, tx, clock, audit);
        InboxPort inbox = new JdbcInboxPort(jdbc, tx);
        RateLimitPort alwaysPermit = (tenantId, actionName) -> true;
        QuestionnairePort questionnaires = new JdbcQuestionnairePort(jdbc, tx, clock, alwaysPermit);
        GenericSubjectRecordExporter exporter = new GenericSubjectRecordExporter(
                jdbc, consents, inbox, questionnaires, objects, clock);
        LocalDownloadSignature signature = new LocalDownloadSignature("test-hmac-key");
        ObjectDownloadProperties properties = new ObjectDownloadProperties(
                ObjectDownloadProperties.DEFAULT_TTL,
                ObjectDownloadProperties.HARD_CAP,
                "http://127.0.0.1:8080",
                "test-hmac-key");
        ObjectDownloadLinkService downloads = new ObjectDownloadLinkService(
                new ObjectAccessAuthorizer(objects, jdbc, clock),
                new LocalHmacDownloadIssuer(signature, properties.publicBaseUrl(), clock),
                signature,
                storage,
                properties,
                clock,
                objectAudit);
        ExportGenerationService generation = new ExportGenerationService(
                store,
                gate,
                new ExportExpiryService(store, clock, new WorkflowExportHook(workflows)),
                List.of(exporter),
                storage,
                objects,
                downloads,
                workflows,
                accounts,
                tenantAccess,
                PolicyEngineFactory.create("sql", new DenyWhenTenantMissing(), tenantAccess),
                audit,
                clock,
                Duration.ofDays(7),
                tx);
        mvc = MockMvcBuilders.standaloneSetup(
                        new EndUserExportEndpoint(service, generation),
                        new OperatorExportEndpoint(service, generation),
                        new ObjectDownloadEndpoint(downloads))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter())
                .build();

        alice = accounts.provision(TENANT_A, "Alice");
        bob = accounts.provision(TENANT_A, "Bob");
        carol = accounts.provision(TENANT_B, "Carol");
        seedInbox(inbox, alice, ALICE_INBOX, "alice body must not leak");
        seedInbox(inbox, bob, BOB_INBOX, "bob body must not leak");
        seedQuestionnaire(questionnaires);

        requester = operator("req", "export.request");
        approver = operator("appr", "export.approve");
        both = operator("both", "export.request", "export.approve");
        grant(requester, TENANT_A);
        grant(approver, TENANT_A);
        grant(both, TENANT_A);
        grant(approver, TENANT_B);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void domainCallerRequireBlocksUntilGrantAndAgainAfterWithdraw() {
        assertThatThrownBy(() -> consents.require(TENANT_A, alice.subject().subjectId(), PURPOSE, VERSION))
                .isInstanceOf(ConsentRequiredException.class);
        consents.grant(
                TENANT_A,
                alice.subject().subjectId(),
                PURPOSE,
                VERSION,
                alice.account().accountId());
        consents.require(TENANT_A, alice.subject().subjectId(), PURPOSE, VERSION);
        consents.withdraw(
                TENANT_A, alice.subject().subjectId(), PURPOSE, alice.account().accountId());
        assertThatThrownBy(() -> consents.require(TENANT_A, alice.subject().subjectId(), PURPOSE, VERSION))
                .isInstanceOf(ConsentRequiredException.class);
        assertThat(countAudit(JdbcConsentPort.AUDIT_GRANT)).isEqualTo(1);
        assertThat(countAudit(JdbcConsentPort.AUDIT_WITHDRAW)).isEqualTo(1);
    }

    @Test
    void exportFlowRedeemOwnQuestionnaireAndInboxOnlyAndDenialsAreAudited() throws Exception {
        String unapproved = openAs(alice);
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + unapproved + "/generate")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ExportNotApprovedException.REASON));
        assertThat(countAudit(ExportApprovalGate.AUDIT_DENY)).isGreaterThanOrEqualTo(1);

        mvc.perform(post(OperatorExportEndpoint.PATH)
                        .with(asPrincipal(both))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\"" + alice.subject().subjectId() + "\",\"scope\":\"ALL_OWN\"}"))
                .andExpect(status().isOk())
                .andDo(result -> {
                    String selfId = mapper.readTree(result.getResponse().getContentAsString())
                            .get("requestId")
                            .asText();
                    try {
                        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + selfId + "/approve")
                                        .with(asPrincipal(both))
                                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.reason").value(ExportSelfApprovalException.REASON));
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                });

        String requestId = openAs(alice);
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + requestId + "/approve")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + requestId + "/generate")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        MvcResult issued = mvc.perform(post(EndUserExportEndpoint.PATH + "/" + requestId + "/download-link")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        URI uri = URI.create(mapper.readTree(issued.getResponse().getContentAsString()).get("url").asText());
        String bundle = mvc.perform(get(uri.getRawPath() + "?" + uri.getRawQuery()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(bundle).contains(ALICE_INBOX);
        assertThat(bundle).contains(ALICE_ANSWER);
        assertThat(bundle).contains(alice.subject().subjectId());
        assertThat(bundle).doesNotContain(BOB_INBOX);
        assertThat(bundle).doesNotContain(BOB_ANSWER);
        assertThat(bundle).doesNotContain(bob.subject().subjectId());
        assertThat(bundle).doesNotContain("bob body must not leak");

        jdbc.update(
                "UPDATE data_export_request SET expires_at = TIMESTAMP '2020-01-01 00:00:00' WHERE request_id = ?",
                requestId);
        mvc.perform(post(EndUserExportEndpoint.PATH + "/" + requestId + "/download-link")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ExportExpiredException.REASON));

        mvc.perform(post(EndUserExportEndpoint.PATH + "/" + requestId + "/download-link")
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + requestId + "/generate")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());
        mvc.perform(post(EndUserExportEndpoint.PATH + "/" + requestId + "/download-link")
                        .with(asEndUser(carol))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void kernelSourcesHaveNoDomainWordingOrSecondOutboxAndStartFromHookIsNotPublic() throws Exception {
        assertThat(Modifier.isPublic(WorkflowService.class
                        .getDeclaredMethod(
                                "startFromHook",
                                String.class,
                                String.class,
                                String.class,
                                String.class,
                                String.class)
                        .getModifiers()))
                .isFalse();
        Path root = repoRoot();
        List<Path> sources = List.of(
                root.resolve("platform-contract/src/main/java/com/subjex/platform/contract/consent"),
                root.resolve("platform-contract/src/main/java/com/subjex/platform/contract/exporthook"),
                root.resolve("platform-app/src/main/java/com/subjex/platform/app/consent"),
                root.resolve("platform-app/src/main/java/com/subjex/platform/app/exporthook"));
        Pattern forbidden = Pattern.compile(
                "(?i)(?<!il)\\bmedical\\b|\\bhospital\\b|\\bvisa\\b|\\binsurance\\b|\\blegal[-_ ]text\\b|医疗|医院|签证|保险|法律正文|法律文本");
        for (Path dir : sources) {
            assertThat(dir).isDirectory();
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String body = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                    assertThat(forbidden.matcher(body).find())
                            .as("%s must not contain medical/hospital/visa/insurance or legal-text wording", file)
                            .isFalse();
                }
            }
        }
        Path migrations = root.resolve("platform-app/src/main/resources/db/migration");
        try (Stream<Path> walk = Files.walk(migrations)) {
            List<Path> second = walk.filter(path -> path.toString().endsWith(".sql"))
                    .filter(ConsentExportAcceptanceTest::createsSecondOutbox)
                    .toList();
            assertThat(second).as("WP8 must reuse outbox_event, not add a second outbox table").isEmpty();
        }
    }

    private void seedInbox(InboxPort inbox, EndUserRecord record, String title, String body) {
        inbox.send(new InboxSendRequest(
                TENANT_A,
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
                List.of(new InboxRecipient(InboxRecipientRealm.END_USER, record.subject().subjectId())),
                clock.instant()));
    }

    private void seedQuestionnaire(QuestionnairePort questionnaires) {
        QuestionnaireDefinition draft =
                questionnaires.create(TENANT_A, "platform-feedback", QuestionnaireCollectMode.AUTH_END_USER);
        questionnaires.replaceItems(
                TENANT_A,
                draft.questionnaireId(),
                List.of(new QuestionnaireItem("i1", 1, QuestionType.TEXT_SHORT, "note", true, null)));
        questionnaires.publish(TENANT_A, draft.questionnaireId());
        questionnaires.submit(
                TENANT_A,
                draft.questionnaireId(),
                QuestionnaireRespondentRealm.END_USER,
                alice.subject().subjectId(),
                List.of(new QuestionnaireAnswer("i1", "\"" + ALICE_ANSWER + "\"")));
        questionnaires.submit(
                TENANT_A,
                draft.questionnaireId(),
                QuestionnaireRespondentRealm.END_USER,
                bob.subject().subjectId(),
                List.of(new QuestionnaireAnswer("i1", "\"" + BOB_ANSWER + "\"")));
    }

    private String openAs(EndUserRecord record) throws Exception {
        MvcResult created = mvc.perform(post(EndUserExportEndpoint.PATH)
                        .with(asEndUser(record))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"ALL_OWN\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readTree(created.getResponse().getContentAsString()).get("requestId").asText();
    }

    private int countAudit(String actionName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE action_name = ?", Integer.class, actionName);
        return count == null ? 0 : count;
    }

    private void grant(OperatorPrincipal operator, String tenantId) {
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                operator.subjectId(),
                tenantId);
    }

    private static OperatorPrincipal operator(String slug, String... permissions) {
        return new OperatorPrincipal("op-" + slug, "hash", "id-" + slug, "sub-" + slug, Set.of(permissions), true);
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

    private static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder out = new StringBuilder(noBlock.length());
        for (String line : noBlock.split("\\R", -1)) {
            int cut = line.indexOf("//");
            out.append(cut >= 0 ? line.substring(0, cut) : line).append('\n');
        }
        return out.toString();
    }

    private static boolean createsSecondOutbox(Path path) {
        try {
            String sql = Files.readString(path, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            for (String line : sql.split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.startsWith("--") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (trimmed.contains("create table")
                        && trimmed.contains("outbox")
                        && !trimmed.contains("outbox_event")) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
