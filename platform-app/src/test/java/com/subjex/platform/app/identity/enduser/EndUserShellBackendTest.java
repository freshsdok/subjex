package com.subjex.platform.app.identity.enduser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.inbox.EndUserShellInboxEndpoint;
import com.subjex.platform.app.inbox.JdbcInboxPort;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.questionnaire.EndUserShellQuestionnaireEndpoint;
import com.subjex.platform.app.questionnaire.JdbcQuestionnairePort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EndUserShellBackendTest — purpose/gate WP7-1: end-user profile, own inbox, AUTH_END_USER
 * questionnaires under {@code /api/v1/end-user/**}; operator tokens rejected; cross-tenant denied.
 * <p>
 * 目的/门禁 WP7-1：终端资料、本人收件箱、认证问卷；拒绝操作员令牌；跨租户失败关闭。
 */
class EndUserShellBackendTest {

    private static final String TENANT_A = "tenant-shell-a";
    private static final String TENANT_B = "tenant-shell-b";
    private static final Instant T0 = Instant.parse("2026-11-20T00:00:00Z");

    private JdbcTemplate jdbc;
    private JdbcEndUserAccountStore accounts;
    private JdbcInboxPort inbox;
    private JdbcQuestionnairePort questionnaires;
    private MockMvc mvc;
    private EndUserRecord alice;
    private EndUserRecord bob;
    private EndUserRecord carolInB;
    private String aliceEntryId;
    private String bobEntryId;
    private String publishedAuthId;
    private String draftId;
    private String publishedAnonId;

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
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        inbox = new JdbcInboxPort(jdbc, tx);
        RateLimitPort alwaysPermit = (tenantId, actionName) -> true;
        questionnaires = new JdbcQuestionnairePort(jdbc, tx, clock, alwaysPermit);
        EndUserShellService shell = new EndUserShellService(
                accounts,
                inbox,
                questionnaires,
                new JdbcAuditPort(jdbc),
                new LocalDirectoryObjectStorage(objectRoot),
                clock);
        mvc = MockMvcBuilders.standaloneSetup(
                        new EndUserProfileEndpoint(shell),
                        new EndUserShellInboxEndpoint(shell),
                        new EndUserShellQuestionnaireEndpoint(shell))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();

        alice = accounts.provision(TENANT_A, "Alice");
        bob = accounts.provision(TENANT_A, "Bob");
        carolInB = accounts.provision(TENANT_B, "Carol");

        aliceEntryId = sendEndUserMail(TENANT_A, alice.subject().subjectId(), "给 Alice", "仅 Alice 可见");
        bobEntryId = sendEndUserMail(TENANT_A, bob.subject().subjectId(), "给 Bob", "仅 Bob 可见");

        QuestionnaireDefinition published = questionnaires.create(TENANT_A, "终端调研", QuestionnaireCollectMode.AUTH_END_USER);
        questionnaires.replaceItems(
                TENANT_A,
                published.questionnaireId(),
                List.of(item("i1", 1, QuestionType.TEXT_SHORT, "建议", true, null)));
        publishedAuthId = questionnaires.publish(TENANT_A, published.questionnaireId()).orElseThrow().questionnaireId();

        QuestionnaireDefinition draft = questionnaires.create(TENANT_A, "草稿", QuestionnaireCollectMode.AUTH_END_USER);
        draftId = draft.questionnaireId();

        QuestionnaireDefinition anon = questionnaires.create(TENANT_A, "匿名调研", QuestionnaireCollectMode.ANON_LINK);
        questionnaires.replaceItems(
                TENANT_A,
                anon.questionnaireId(),
                List.of(item("i1", 1, QuestionType.TEXT_SHORT, "意见", true, null)));
        publishedAnonId = questionnaires.publish(TENANT_A, anon.questionnaireId()).orElseThrow().questionnaireId();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void profileGetAndPatchUpdatesSubjectNameAndWritesAudit() throws Exception {
        mvc.perform(get(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(alice.account().accountId()))
                .andExpect(jsonPath("$.subjectId").value(alice.subject().subjectId()))
                .andExpect(jsonPath("$.subjectName").value("Alice"));

        mvc.perform(patch(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"Alice Zhang\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectName").value("Alice Zhang"));

        assertThat(accounts.findByAccountId(alice.account().accountId()).orElseThrow().subject().subjectName())
                .isEqualTo("Alice Zhang");
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND action_target = ?",
                Integer.class,
                EndUserShellService.PROFILE_UPDATE_ACTION,
                alice.subject().subjectId());
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void profileRejectsBlankAndOverlongSubjectName() throws Exception {
        mvc.perform(patch(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"   \"}"))
                .andExpect(status().isBadRequest());

        String tooLong = "n".repeat(EndUserAccountStore.MAX_DISPLAY_NAME_LENGTH + 1);
        mvc.perform(patch(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void inboxListsOnlyOwnMessagesAndHidesAnotherUsersEntry() throws Exception {
        mvc.perform(get(EndUserShellInboxEndpoint.BASE + "/entries")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].entryId").value(aliceEntryId))
                .andExpect(jsonPath("$.entries[0].title").value("给 Alice"));

        mvc.perform(get(EndUserShellInboxEndpoint.BASE + "/unread-count")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mvc.perform(get(EndUserShellInboxEndpoint.BASE + "/entries/" + bobEntryId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());

        mvc.perform(post(EndUserShellInboxEndpoint.BASE + "/entries/" + bobEntryId + "/read")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());

        mvc.perform(post(EndUserShellInboxEndpoint.BASE + "/entries/" + aliceEntryId + "/read")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk());

        mvc.perform(get(EndUserShellInboxEndpoint.BASE + "/unread-count")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void questionnaireListsPublishedAuthOnlyAndSubmitRecordsEndUserSubjectOnce() throws Exception {
        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionnaires.length()").value(1))
                .andExpect(jsonPath("$.questionnaires[0].questionnaireId").value(publishedAuthId))
                .andExpect(jsonPath("$.questionnaires[0].collectMode").value("AUTH_END_USER"));

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE + "/" + draftId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());
        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE + "/" + publishedAnonId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE + "/" + publishedAuthId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("终端调研"));

        mvc.perform(post(EndUserShellQuestionnaireEndpoint.BASE + "/" + publishedAuthId + "/responses")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"itemId\":\"i1\",\"valueJson\":\"\\\"ok\\\"\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respondentRealm").value("END_USER"))
                .andExpect(jsonPath("$.respondentSubjectId").value(alice.subject().subjectId()));

        mvc.perform(post(EndUserShellQuestionnaireEndpoint.BASE + "/" + publishedAuthId + "/responses")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"itemId\":\"i1\",\"valueJson\":\"\\\"again\\\"\"}]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void operatorPrincipalIsRejectedOnEndUserShellRoutes() throws Exception {
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
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("end-user-authentication-required"));
        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE)
                        .with(asPrincipal(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("end-user-authentication-required"));
    }

    @Test
    void reservedPlatformAndCrossTenantAreDenied() throws Exception {
        mvc.perform(get(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, EndUserAccountStore.RESERVED_TENANT))
                .andExpect(status().isBadRequest());

        mvc.perform(get(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(EndUserTenantAccessDeniedException.REASON));

        mvc.perform(get(EndUserShellInboxEndpoint.BASE + "/entries")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE)
                        .with(asEndUser(carolInB))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        mvc.perform(get(EndUserProfileEndpoint.PATH).with(asEndUser(alice)))
                .andExpect(status().isForbidden());
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

    private static QuestionnaireItem item(
            String id, int order, QuestionType type, String prompt, boolean required, String options) {
        return new QuestionnaireItem(id, order, type, prompt, required, options);
    }
}
