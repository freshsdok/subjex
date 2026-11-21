package com.subjex.platform.app.exporthook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.entity.declare.EntityDefinitionRejected;
import com.subjex.entity.declare.EntityRenderer;
import com.subjex.entity.declare.FieldSensitivity;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.identity.enduser.EndUserExportEndpoint;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserRecord;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.PolicyEngineFactory;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.JdbcObjectGrantStore;
import com.subjex.platform.app.storage.ObjectGrantStore;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.workflow.JdbcWorkflowStore;
import com.subjex.platform.app.workflow.WorkflowCatalog;
import com.subjex.platform.app.workflow.WorkflowService;
import com.subjex.platform.contract.exporthook.ExportNotApprovedException;
import com.subjex.platform.contract.exporthook.ExportRequest;
import com.subjex.platform.contract.exporthook.ExportRequestStatus;
import com.subjex.platform.contract.exporthook.ExportSelfApprovalException;
import com.subjex.platform.contract.exporthook.SensitivityClass;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import java.time.Clock;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * ExportApprovalTest — purpose/gate WP8-2: export request, thin-workflow approval, gate, isolation, sensitivity.
 * 目的/门禁 WP8-2：导出申请、薄工作流审批、门禁、隔离与敏感级别。
 */
class ExportApprovalTest {

    private static final String TENANT_A = "tenant-wp82-a";
    private static final String TENANT_B = "tenant-wp82-b";

    private JdbcTemplate jdbc;
    private ExportRequestService service;
    private ExportApprovalGate gate;
    private ObjectGrantStore objects;
    private MockMvc mvc;
    private EndUserRecord alice;
    private EndUserRecord bob;
    private OperatorPrincipal requester;
    private OperatorPrincipal approver;
    private OperatorPrincipal both;
    private OperatorPrincipal none;
    private final ObjectMapper mapper = new ObjectMapper();

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
        Clock clock = Clock.system(ZoneOffset.UTC);
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
        gate = new ExportApprovalGate(store, audit, clock);
        service = new ExportRequestService(
                jdbc,
                store,
                workflows,
                accounts,
                tenantAccess,
                PolicyEngineFactory.create("sql", new DenyWhenTenantMissing(), tenantAccess),
                audit,
                clock);
        objects = new JdbcObjectGrantStore(jdbc, clock);
        mvc = MockMvcBuilders.standaloneSetup(
                        new EndUserExportEndpoint(service), new OperatorExportEndpoint(service))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();

        alice = accounts.provision(TENANT_A, "Alice");
        bob = accounts.provision(TENANT_A, "Bob");
        accounts.provision(TENANT_B, "Carol");

        requester = operator("req", "export.request");
        approver = operator("appr", "export.approve");
        both = operator("both", "export.request", "export.approve");
        none = operator("none");
        grant(requester, TENANT_A);
        grant(approver, TENANT_A);
        grant(both, TENANT_A);
        grant(none, TENANT_A);
        grant(approver, TENANT_B);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void endUserRequestOperatorApproveThenGatePassesAndRejectFails() throws Exception {
        MvcResult created = mvc.perform(post(EndUserExportEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"ALL_OWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.subjectId").value(alice.subject().subjectId()))
                .andReturn();
        String requestId = mapper.readTree(created.getResponse().getContentAsString())
                .get("requestId")
                .asText();
        assertThatThrownBy(() -> gate.requireApproved(requestId))
                .isInstanceOf(ExportNotApprovedException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_entry WHERE action_name = ?",
                        Integer.class,
                        ExportApprovalGate.AUDIT_DENY))
                .isEqualTo(1);

        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + requestId + "/approve")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        ExportRequest approved = gate.requireApproved(requestId);
        assertThat(approved.status()).isEqualTo(ExportRequestStatus.APPROVED);
        assertThat(approved.workflowInstanceId()).isNotBlank();

        MvcResult other = mvc.perform(post(EndUserExportEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"ALL_OWN\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String rejectedId = mapper.readTree(other.getResponse().getContentAsString())
                .get("requestId")
                .asText();
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + rejectedId + "/reject")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThatThrownBy(() -> gate.requireApproved(rejectedId))
                .isInstanceOf(ExportNotApprovedException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
    }

    @Test
    void selfApproveAndMissingPermissionsAndCrossTenantAndOtherSubjectAreDenied() throws Exception {
        MvcResult created = mvc.perform(post(OperatorExportEndpoint.PATH)
                        .with(asPrincipal(both))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\""
                                + alice.subject().subjectId()
                                + "\",\"scope\":\"ALL_OWN\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String requestId = mapper.readTree(created.getResponse().getContentAsString())
                .get("requestId")
                .asText();

        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + requestId + "/approve")
                        .with(asPrincipal(both))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ExportSelfApprovalException.REASON));

        mvc.perform(post(OperatorExportEndpoint.PATH)
                        .with(asPrincipal(none))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectId\":\""
                                + alice.subject().subjectId()
                                + "\",\"scope\":\"ALL_OWN\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + requestId + "/approve")
                        .with(asPrincipal(requester))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        mvc.perform(get(OperatorExportEndpoint.PATH)
                        .with(asPrincipal(requester))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());

        mvc.perform(get(EndUserExportEndpoint.PATH)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(EndUserExportEndpoint.PATH + "/" + requestId)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());
    }

    @Test
    void sensitivityDefaultsToNormalAndValidates() {
        RenderedEntity entity = new EntityRenderer().render("""
                entityKey: sample-item
                tableName: sample_item
                version: 1
                permission: registry.read
                tenantScoped: true
                fields:
                  - name: itemId
                    kind: text
                    required: true
                    maxLength: 64
                  - name: secretNote
                    kind: text
                    required: false
                    maxLength: 128
                    sensitivity: SENSITIVE
                """);
        assertThat(entity.fields().get(0).sensitivity()).isEqualTo(FieldSensitivity.NORMAL);
        assertThat(entity.fields().get(1).sensitivity()).isEqualTo(FieldSensitivity.SENSITIVE);
        assertThatThrownBy(() -> new EntityRenderer().render("""
                entityKey: sample-item
                tableName: sample_item
                version: 1
                permission: registry.read
                tenantScoped: true
                fields:
                  - name: itemId
                    kind: text
                    required: true
                    maxLength: 64
                    sensitivity: SECRET
                """))
                .isInstanceOf(EntityDefinitionRejected.class);

        objects.register(TENANT_A, "file-one", alice.subject().subjectId());
        assertThat(objects.objectSensitivity(TENANT_A, "file-one")).isEqualTo(SensitivityClass.NORMAL);
        objects.setObjectSensitivity(TENANT_A, "file-one", SensitivityClass.SENSITIVE);
        assertThat(objects.objectSensitivity(TENANT_A, "file-one")).isEqualTo(SensitivityClass.SENSITIVE);
        assertThatThrownBy(() -> SensitivityClass.parse("SECRET"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void grant(OperatorPrincipal operator, String tenantId) {
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                operator.subjectId(),
                tenantId);
    }

    private static OperatorPrincipal operator(String slug, String... permissions) {
        return new OperatorPrincipal(
                "op-" + slug,
                "hash",
                "id-" + slug,
                "sub-" + slug,
                Set.of(permissions),
                true);
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
}
