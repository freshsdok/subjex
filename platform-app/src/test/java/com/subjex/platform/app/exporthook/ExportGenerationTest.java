package com.subjex.platform.app.exporthook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.consent.JdbcConsentPort;
import com.subjex.platform.app.identity.enduser.EndUserExportEndpoint;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserRecord;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
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
import com.subjex.platform.contract.exporthook.ExportArtifact;
import com.subjex.platform.contract.exporthook.ExportExpiredException;
import com.subjex.platform.contract.exporthook.SubjectRecordExporter;
import com.subjex.platform.contract.exporthook.ExportNotApprovedException;
import com.subjex.platform.contract.exporthook.SensitivityClass;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
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
 * ExportGenerationTest — purpose/gate WP8-3: exporter SPI, generate, signed download, expiry, hook isolation.
 * 目的/门禁 WP8-3：导出 SPI、生成、签名下载、过期与钩子隔离。
 */
class ExportGenerationTest {

    private static final String TENANT_A = "tenant-wp83-a";
    private static final String TENANT_B = "tenant-wp83-b";
    private static final String ALICE_PURPOSE = "alice.export.marker";
    private static final String BOB_PURPOSE = "bob.export.marker";
    private static final String ALICE_OBJECT = "alice-obj-wp83";
    private static final String BOB_OBJECT = "bob-obj-wp83";

    private JdbcTemplate jdbc;
    private ExportApprovalGate gate;
    private ObjectGrantStore objects;
    private MockMvc mvc;
    private EndUserRecord alice;
    private EndUserRecord bob;
    private EndUserRecord carol;
    private OperatorPrincipal requester;
    private OperatorPrincipal approver;
    private ExportGenerationService generation;
    private final ToggleExporter failingExporter = new ToggleExporter();
    private final ObjectMapper mapper = new ObjectMapper();

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
        objects = new JdbcObjectGrantStore(jdbc, clock, objectAudit);
        ObjectStorage storage = new LocalDirectoryObjectStorage(objectRoot);
        ConsentPort consents = new JdbcConsentPort(jdbc, tx, clock, audit);
        GenericSubjectRecordExporter exporter = new GenericSubjectRecordExporter(
                jdbc, consents, null, null, objects, clock);
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
        ExportExpiryService expiry = new ExportExpiryService(store, clock, new WorkflowExportHook(workflows));
        generation = new ExportGenerationService(
                store,
                gate,
                expiry,
                List.of(failingExporter, exporter),
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
        consents.grant(
                TENANT_A,
                alice.subject().subjectId(),
                ALICE_PURPOSE,
                "v1",
                alice.account().accountId());
        consents.grant(
                TENANT_A, bob.subject().subjectId(), BOB_PURPOSE, "v1", bob.account().accountId());
        storage.put(TENANT_A, ALICE_OBJECT, "alice-bytes".getBytes(StandardCharsets.UTF_8), "text/plain");
        storage.put(TENANT_A, BOB_OBJECT, "bob-bytes".getBytes(StandardCharsets.UTF_8), "text/plain");
        objects.register(TENANT_A, ALICE_OBJECT, alice.subject().subjectId());
        objects.register(TENANT_A, BOB_OBJECT, bob.subject().subjectId());
        objects.setObjectSensitivity(TENANT_A, ALICE_OBJECT, SensitivityClass.SENSITIVE);

        requester = operator("req", "export.request");
        approver = operator("appr", "export.approve");
        grant(requester, TENANT_A);
        grant(approver, TENANT_A);
        grant(approver, TENANT_B);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void approveGenerateEndUserLinkRedeemContainsOnlyOwnData() throws Exception {
        String requestId = openAndApprove(alice);
        mvc.perform(get(OperatorExportEndpoint.PATH + "/" + requestId)
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.artifactObjectKey").isEmpty());
        // Approval auto-enqueues; HTTP generate must not write the artifact on the request thread.
        // 批准后自动入队；HTTP generate 不得在请求线程写产物。
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + requestId + "/generate")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GENERATING"));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND action_target = ?",
                        Integer.class,
                        ExportGenerationService.AUDIT_GENERATE,
                        requestId))
                .isZero();
        assertThat(generation.processQueued(10)).isEqualTo(1);
        mvc.perform(get(OperatorExportEndpoint.PATH + "/" + requestId)
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.artifactObjectKey").value("export-" + requestId));
        assertThat(objects.objectSensitivity(TENANT_A, "export-" + requestId))
                .isEqualTo(SensitivityClass.SENSITIVE);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND action_target = ?",
                        Integer.class,
                        ExportGenerationService.AUDIT_GENERATE,
                        requestId))
                .isEqualTo(1);

        MvcResult issued = mvc.perform(post(EndUserExportEndpoint.PATH + "/" + requestId + "/download-link")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isNotEmpty())
                .andReturn();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND action_target = ?",
                        Integer.class,
                        ExportGenerationService.AUDIT_DOWNLOAD_LINK,
                        requestId))
                .isEqualTo(1);
        URI uri = URI.create(mapper.readTree(issued.getResponse().getContentAsString()).get("url").asText());
        MvcResult redeemed = mvc.perform(get(uri.getRawPath() + "?" + uri.getRawQuery()))
                .andExpect(status().isOk())
                .andReturn();
        String json = redeemed.getResponse().getContentAsString();
        JsonNode bundle = mapper.readTree(json);
        assertThat(bundle.get("subjectId").asText()).isEqualTo(alice.subject().subjectId());
        assertThat(bundle.get("artifactSensitivity").asText()).isEqualTo("SENSITIVE");
        assertThat(bundle.get("profile").get("subjectName").asText()).isEqualTo("Alice");
        assertThat(json).contains(ALICE_PURPOSE);
        assertThat(json).contains(ALICE_OBJECT);
        assertThat(json).doesNotContain(BOB_PURPOSE);
        assertThat(json).doesNotContain(BOB_OBJECT);
        assertThat(json).doesNotContain(bob.subject().subjectId());
        assertThat(json).doesNotContain("bob-bytes");
        assertThat(bundle.get("consents").get("sensitivity").asText()).isEqualTo("SENSITIVE");
    }

    @Test
    void generateDeniedWhenUnapprovedOrRejectedAndRequesterCannotGenerate() throws Exception {
        String requested = openAs(alice);
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + requested + "/generate")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ExportNotApprovedException.REASON));
        mvc.perform(post(EndUserExportEndpoint.PATH + "/" + requested + "/download-link")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        String rejected = openAs(alice);
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + rejected + "/reject")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk());
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + rejected + "/generate")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ExportNotApprovedException.REASON));
        mvc.perform(post(EndUserExportEndpoint.PATH + "/" + rejected + "/download-link")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        String approved = openAndApprove(alice);
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + approved + "/generate")
                        .with(asPrincipal(requester))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredRequestYieldsNoLinkAndStatusBecomesExpired() throws Exception {
        String requestId = openAndApprove(alice);
        assertThat(generation.processQueued(10)).isEqualTo(1);
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
        mvc.perform(get(EndUserExportEndpoint.PATH + "/" + requestId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void anotherEndUserAndCrossTenantCannotGetLinkOrGenerate() throws Exception {
        String requestId = openAndApprove(alice);
        assertThat(generation.processQueued(10)).isEqualTo(1);
        mvc.perform(post(EndUserExportEndpoint.PATH + "/" + requestId + "/download-link")
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(EndUserExportEndpoint.PATH + "/" + requestId + "/download-link")
                        .with(asEndUser(carol))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + requestId + "/download-link")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + requestId + "/generate")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());
    }

    @Test
    void startFromHookIsNotPublicAndAllowlistRejectsForeignKeys() throws Exception {
        Method method = WorkflowService.class.getDeclaredMethod(
                "startFromHook", String.class, String.class, String.class, String.class, String.class);
        assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
        assertThatThrownBy(() -> WorkflowService.class.getMethod(
                        "startFromHook", String.class, String.class, String.class, String.class, String.class))
                .isInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() -> new WorkflowExportHook(null))
                .isInstanceOf(NullPointerException.class);
        WorkflowExportHook hook = new WorkflowExportHook(unusedWorkflowService());
        assertThatThrownBy(() -> hook.start("repair-ticket", TENANT_A, "repair-ticket", "rec-1", "sub-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data-export");
        assertThat(startFromHookCallSites())
                .containsExactlyInAnyOrder(
                        "WorkflowService.java",
                        "WorkflowExportHook.java");
    }

    private WorkflowService unusedWorkflowService() {
        // Only used to construct the hook for the allow-list check (fails before the service is invoked).
        // 仅用于构造钩子以校验白名单（在调用服务之前就会失败）。
        return new WorkflowService(
                new JdbcWorkflowStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))),
                new JdbcOperatorTenantAccess(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))),
                new CapabilityCatalog(),
                new OperatorActionAudit(new JdbcAuditPort(jdbc), Clock.system(ZoneOffset.UTC)),
                Clock.system(ZoneOffset.UTC),
                null,
                null,
                null,
                new WorkflowCatalog());
    }

    private static List<String> startFromHookCallSites() throws Exception {
        Path root = Path.of("src/main/java");
        if (!Files.isDirectory(root)) {
            root = Path.of("platform-app/src/main/java");
        }
        assertThat(root).isDirectory();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("startFromHook");
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    })
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }


    @Test
    void failedGenerationCanBeRetriedAndThenSucceeds() throws Exception {
        String requestId = openAndApprove(alice);
        failingExporter.fail = true;
        assertThat(generation.processQueued(10)).isZero();
        mvc.perform(get(OperatorExportEndpoint.PATH + "/" + requestId)
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        failingExporter.fail = false;
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + requestId + "/generate")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GENERATING"));
        assertThat(generation.processQueued(10)).isEqualTo(1);
        mvc.perform(get(OperatorExportEndpoint.PATH + "/" + requestId)
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void exportListsPageByKeysetCursorNewestFirst() throws Exception {
        String first = openAs(alice);
        String second = openAs(alice);
        String third = openAs(alice);
        MvcResult page1 = mvc.perform(get(EndUserExportEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exports.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        var root = mapper.readTree(page1.getResponse().getContentAsString());
        assertThat(root.get("exports").get(0).get("requestId").asText()).isEqualTo(third);
        assertThat(root.get("exports").get(1).get("requestId").asText()).isEqualTo(second);
        String cursor = root.get("nextCursor").asText();
        mvc.perform(get(EndUserExportEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("limit", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exports.length()").value(1))
                .andExpect(jsonPath("$.exports[0].requestId").value(first))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        mvc.perform(get(OperatorExportEndpoint.PATH)
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exports.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty());
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

    private String openAndApprove(EndUserRecord record) throws Exception {
        String requestId = openAs(record);
        mvc.perform(post(OperatorExportEndpoint.PATH + "/" + requestId + "/approve")
                        .with(asPrincipal(approver))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk());
        return requestId;
    }

    private void grant(OperatorPrincipal operator, String tenantId) {
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                operator.subjectId(),
                tenantId);
    }

    private static final class ToggleExporter implements SubjectRecordExporter {
        volatile boolean fail;

        @Override
        public boolean supports(String tenantId, String subjectId, String scopeDescriptor) {
            return fail;
        }

        @Override
        public ExportArtifact export(String tenantId, String subjectId, String scopeDescriptor) {
            throw new IllegalStateException("export failed on purpose");
        }

        @Override
        public void exportTo(String tenantId, String subjectId, String scopeDescriptor, java.io.OutputStream output) {
            throw new IllegalStateException("export failed on purpose");
        }
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
