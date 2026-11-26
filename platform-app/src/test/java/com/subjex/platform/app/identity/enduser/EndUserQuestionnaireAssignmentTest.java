package com.subjex.platform.app.identity.enduser;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.inbox.JdbcInboxPort;
import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.organization.RelationKind;
import com.subjex.platform.app.questionnaire.EndUserShellQuestionnaireEndpoint;
import com.subjex.platform.app.questionnaire.JdbcQuestionnaireAssignmentStore;
import com.subjex.platform.app.questionnaire.JdbcQuestionnairePort;
import com.subjex.platform.app.questionnaire.QuestionnaireAssigneeKind;
import com.subjex.platform.app.questionnaire.QuestionnaireAssignment;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.questionnaire.QuestionType;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectMode;
import com.subjex.platform.contract.questionnaire.QuestionnaireDefinition;
import com.subjex.platform.contract.questionnaire.QuestionnaireItem;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * EndUserQuestionnaireAssignmentTest — published AUTH_END_USER questionnaires are visible
 * only when assigned to the subject or a department they belong to.
 * 已发布的认证问卷仅在分配给本人或所属部门时可见、可填。
 */
class EndUserQuestionnaireAssignmentTest {

    private static final String TENANT_A = "tenant-q-acl-a";
    private static final Instant T0 = Instant.parse("2026-11-26T13:30:00Z");

    @TempDir
    Path objectRoot;

    private JdbcQuestionnairePort questionnaires;
    private JdbcQuestionnaireAssignmentStore assignments;
    private JdbcOrganizationStore orgs;
    private MockMvc mvc;
    private EndUserRecord alice;
    private EndUserRecord bob;
    private EndUserRecord parentOnly;
    private String publishedId;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_A,
                "Tenant A");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        JdbcEndUserAccountStore accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        JdbcInboxPort inbox = new JdbcInboxPort(jdbc, tx);
        RateLimitPort alwaysPermit = (tenantId, actionName) -> true;
        questionnaires = new JdbcQuestionnairePort(jdbc, tx, clock, alwaysPermit);
        EndUserShellFixtures.Kit kit = EndUserShellFixtures.shell(
                accounts, inbox, questionnaires, jdbc, tx, clock, new LocalDirectoryObjectStorage(objectRoot));
        assignments = kit.assignments();
        orgs = kit.organizations();
        mvc = MockMvcBuilders.standaloneSetup(new EndUserShellQuestionnaireEndpoint(kit.shell()))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();

        alice = accounts.provision(TENANT_A, "Alice");
        bob = accounts.provision(TENANT_A, "Bob");
        parentOnly = accounts.provision(TENANT_A, "Pat");

        orgs.upsertOrganization("dept-parent", "Parent", JdbcOrganizationStore.STATE_ACTIVE);
        orgs.upsertOrganization("dept-child", "Child", JdbcOrganizationStore.STATE_ACTIVE);
        orgs.upsertTenantOrganization(TENANT_A, "dept-parent", JdbcOrganizationStore.STATE_ACTIVE);
        orgs.upsertTenantOrganization(TENANT_A, "dept-child", JdbcOrganizationStore.STATE_ACTIVE);
        orgs.upsertRelation("dept-parent", "dept-child", RelationKind.CONTAINS, JdbcOrganizationStore.STATE_ACTIVE);
        orgs.upsertMembership(alice.subject().subjectId(), "dept-child", JdbcOrganizationStore.STATE_ACTIVE);
        orgs.upsertMembership(parentOnly.subject().subjectId(), "dept-parent", JdbcOrganizationStore.STATE_ACTIVE);
        orgs.upsertMembership(bob.subject().subjectId(), "dept-child", JdbcOrganizationStore.STATE_SUSPENDED);

        QuestionnaireDefinition published =
                questionnaires.create(TENANT_A, "终端调研", QuestionnaireCollectMode.AUTH_END_USER);
        questionnaires.replaceItems(
                TENANT_A,
                published.questionnaireId(),
                List.of(new QuestionnaireItem("i1", 1, QuestionType.TEXT_SHORT, "建议", true, null)));
        publishedId = questionnaires.publish(TENANT_A, published.questionnaireId()).orElseThrow().questionnaireId();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publishedButUnassignedIsInvisibleAndNotFillable() throws Exception {
        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionnaires.length()").value(0));

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE + "/" + publishedId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());

        mvc.perform(post(EndUserShellQuestionnaireEndpoint.BASE + "/" + publishedId + "/responses")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"itemId\":\"i1\",\"valueJson\":\"\\\"ok\\\"\"}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void subjectAssignmentIsVisibleOnlyToThatPerson() throws Exception {
        assign(QuestionnaireAssigneeKind.SUBJECT, alice.subject().subjectId());

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionnaires.length()").value(1))
                .andExpect(jsonPath("$.questionnaires[0].questionnaireId").value(publishedId));

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE + "/" + publishedId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk());

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionnaires.length()").value(0));

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE + "/" + publishedId)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());
    }

    @Test
    void departmentAssignmentIncludesDescendantMembersButNotSuspendedOrParentOnly() throws Exception {
        assign(QuestionnaireAssigneeKind.ORGANIZATION, "dept-parent");

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionnaires.length()").value(1));

        mvc.perform(post(EndUserShellQuestionnaireEndpoint.BASE + "/" + publishedId + "/responses")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"itemId\":\"i1\",\"valueJson\":\"\\\"ok\\\"\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respondentSubjectId").value(alice.subject().subjectId()));

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionnaires.length()").value(0));

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE)
                        .with(asEndUser(parentOnly))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionnaires.length()").value(1));

        assignments.delete(
                TENANT_A,
                publishedId,
                assignments.list(TENANT_A, publishedId).get(0).assignmentId());
        assign(QuestionnaireAssigneeKind.ORGANIZATION, "dept-child");

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionnaires.length()").value(1));

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE)
                        .with(asEndUser(parentOnly))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionnaires.length()").value(0));

        mvc.perform(get(EndUserShellQuestionnaireEndpoint.BASE + "/" + publishedId)
                        .with(asEndUser(parentOnly))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());
    }

    private void assign(QuestionnaireAssigneeKind kind, String assigneeId) {
        assignments.insert(new QuestionnaireAssignment(
                UUID.randomUUID().toString(),
                TENANT_A,
                publishedId,
                kind,
                assigneeId,
                T0,
                "test"));
    }

    private RequestPostProcessor asEndUser(EndUserRecord record) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                    new EndUserPrincipal(
                            record.account().accountId(),
                            record.subject().subjectId(),
                            record.account().loginName(),
                            true),
                    "n/a",
                    List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }
}
