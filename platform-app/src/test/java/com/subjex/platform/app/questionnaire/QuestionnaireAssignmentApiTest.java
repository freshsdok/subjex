package com.subjex.platform.app.questionnaire;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserRecord;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectMode;
import com.subjex.platform.contract.questionnaire.QuestionnaireDefinition;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * QuestionnaireAssignmentApiTest — operator assignment API security and validation.
 * 操作员分配接口的门禁与校验。
 */
class QuestionnaireAssignmentApiTest {

    private static final String TENANT_A = "tenant-assign-a";
    private static final String TENANT_B = "tenant-assign-b";
    private static final Instant T0 = Instant.parse("2026-11-26T13:00:00Z");

    private JdbcTemplate jdbc;
    private JdbcQuestionnairePort questionnaires;
    private JdbcEndUserAccountStore accounts;
    private JdbcOrganizationStore orgs;
    private MockMvc mvc;
    private EndUserRecord alice;
    private String authId;
    private String anonId;
    private final ObjectMapper mapper = new ObjectMapper();

    private final OperatorPrincipal writer = new OperatorPrincipal(
            "writer",
            "hash",
            "id-writer",
            "sub-writer",
            Set.of(
                    OperatorPermission.QUESTIONNAIRE_READ.permissionName(),
                    OperatorPermission.QUESTIONNAIRE_WRITE.permissionName()),
            true);
    private final OperatorPrincipal reader = new OperatorPrincipal(
            "reader",
            "hash",
            "id-reader",
            "sub-reader",
            Set.of(OperatorPermission.QUESTIONNAIRE_READ.permissionName()),
            true);
    private final OperatorPrincipal otherTenant = new OperatorPrincipal(
            "other",
            "hash",
            "id-other",
            "sub-other",
            Set.of(
                    OperatorPermission.QUESTIONNAIRE_READ.permissionName(),
                    OperatorPermission.QUESTIONNAIRE_WRITE.permissionName()),
            true);

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
        questionnaires = new JdbcQuestionnairePort(jdbc, tx, clock, (tenantId, actionName) -> true);
        accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        orgs = new JdbcOrganizationStore(jdbc);
        JdbcOperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        tenantAccess.ensureGrant(writer.subjectId(), TENANT_A);
        tenantAccess.ensureGrant(reader.subjectId(), TENANT_A);
        tenantAccess.ensureGrant(otherTenant.subjectId(), TENANT_B);
        QuestionnaireAssignmentService service = new QuestionnaireAssignmentService(
                questionnaires,
                new JdbcQuestionnaireAssignmentStore(jdbc),
                orgs,
                jdbc,
                tenantAccess,
                new DenyWhenTenantMissing(),
                new JdbcAuditPort(jdbc),
                clock);
        mvc = MockMvcBuilders.standaloneSetup(new QuestionnaireAssignmentEndpoint(service))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        alice = accounts.provision(TENANT_A, "Alice");
        accounts.provision(TENANT_B, "Carol");
        orgs.upsertOrganization("dept-sales", "Sales", JdbcOrganizationStore.STATE_ACTIVE);
        orgs.upsertTenantOrganization(TENANT_A, "dept-sales", JdbcOrganizationStore.STATE_ACTIVE);

        QuestionnaireDefinition auth = questionnaires.create(TENANT_A, "终端调研", QuestionnaireCollectMode.AUTH_END_USER);
        authId = auth.questionnaireId();
        QuestionnaireDefinition anon = questionnaires.create(TENANT_A, "匿名调研", QuestionnaireCollectMode.ANON_LINK);
        anonId = anon.questionnaireId();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void writerCanAddListAndRemoveAssignments() throws Exception {
        MvcResult added = mvc.perform(post(path(authId))
                        .with(asPrincipal(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeKind\":\"SUBJECT\",\"assigneeId\":\""
                                + alice.subject().subjectId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeKind").value("SUBJECT"))
                .andExpect(jsonPath("$.assigneeId").value(alice.subject().subjectId()))
                .andReturn();
        String assignmentId =
                mapper.readTree(added.getResponse().getContentAsString()).path("assignmentId").asText();

        mvc.perform(post(path(authId))
                        .with(asPrincipal(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeKind\":\"SUBJECT\",\"assigneeId\":\""
                                + alice.subject().subjectId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentId").value(assignmentId));

        mvc.perform(post(path(authId))
                        .with(asPrincipal(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeKind\":\"ORGANIZATION\",\"assigneeId\":\"dept-sales\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeKind").value("ORGANIZATION"));

        mvc.perform(get(path(authId))
                        .with(asPrincipal(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.length()").value(2));

        mvc.perform(delete(path(authId) + "/" + assignmentId)
                        .with(asPrincipal(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNoContent());

        mvc.perform(get(path(authId))
                        .with(asPrincipal(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.length()").value(1));
    }

    @Test
    void readerCannotAddOrRemoveAndAnonOrUnknownAreRejected() throws Exception {
        mvc.perform(post(path(authId))
                        .with(asPrincipal(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeKind\":\"SUBJECT\",\"assigneeId\":\""
                                + alice.subject().subjectId() + "\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post(path(anonId))
                        .with(asPrincipal(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeKind\":\"SUBJECT\",\"assigneeId\":\""
                                + alice.subject().subjectId() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("assignments apply only to AUTH_END_USER questionnaires"));

        mvc.perform(post(path("missing-q"))
                        .with(asPrincipal(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeKind\":\"SUBJECT\",\"assigneeId\":\""
                                + alice.subject().subjectId() + "\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(post(path(authId))
                        .with(asPrincipal(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeKind\":\"SUBJECT\",\"assigneeId\":\"nobody\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("assignee is not an active member of this tenant"));

        mvc.perform(post(path(authId))
                        .with(asPrincipal(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeKind\":\"ORGANIZATION\",\"assigneeId\":\"dept-missing\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get(path(authId))
                        .with(asPrincipal(otherTenant))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        mvc.perform(get(path(authId))
                        .with(asPrincipal(new EndUserPrincipal(
                                alice.account().accountId(),
                                alice.subject().subjectId(),
                                alice.account().loginName(),
                                true)))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isUnauthorized());
    }

    private static String path(String questionnaireId) {
        return QuestionnaireEndpoint.BASE + "/" + questionnaireId + "/assignments";
    }

    private static RequestPostProcessor asPrincipal(Object principal) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }
}
