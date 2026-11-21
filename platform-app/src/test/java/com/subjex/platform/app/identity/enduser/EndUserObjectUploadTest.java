package com.subjex.platform.app.identity.enduser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
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
import com.subjex.platform.app.storage.ObjectUploadLimits;
import com.subjex.platform.app.storage.ObjectUploadRejectedException;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * EndUserObjectUploadTest — purpose/gate WP7-2: end-user multipart upload, list, and HMAC redeem.
 * Another user cannot list or download; oversize/MIME rejected; operator and reserved tenant denied.
 * <p>
 * 目的/门禁 WP7-2：终端分件上传、列表与本地 HMAC 兑换。他人不可见；超限与非法 MIME 拒绝；操作员与保留租户拒绝。
 */
class EndUserObjectUploadTest {

    private static final String TENANT_A = "tenant-obj-a";
    private static final String TENANT_B = "tenant-obj-b";
    private static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");
    private static final int TEST_CAP = 16;
    private static final byte[] PAYLOAD = "hello-wp7-2".getBytes(StandardCharsets.UTF_8);

    private JdbcTemplate jdbc;
    private JdbcEndUserAccountStore accounts;
    private JdbcObjectGrantStore grants;
    private LocalDirectoryObjectStorage storage;
    private MockMvc mvc;
    private EndUserRecord alice;
    private EndUserRecord bob;
    private EndUserRecord carolInB;
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
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        ObjectAccessAudit audit = new ObjectAccessAudit(new JdbcAuditPort(jdbc), clock);
        grants = new JdbcObjectGrantStore(jdbc, clock, audit);
        storage = new LocalDirectoryObjectStorage(objectRoot);
        EndUserObjectService objects = new EndUserObjectService(
                accounts, storage, grants, new ObjectUploadLimits(TEST_CAP), audit);
        LocalDownloadSignature signature = new LocalDownloadSignature("test-hmac-key");
        ObjectDownloadProperties properties = new ObjectDownloadProperties(
                ObjectDownloadProperties.DEFAULT_TTL,
                ObjectDownloadProperties.HARD_CAP,
                "http://127.0.0.1:8080",
                "test-hmac-key");
        ObjectAccessAuthorizer authorizer = new ObjectAccessAuthorizer(grants, jdbc, clock);
        ObjectDownloadLinkService downloads = new ObjectDownloadLinkService(
                authorizer,
                new LocalHmacDownloadIssuer(signature, properties.publicBaseUrl(), clock),
                signature,
                storage,
                properties,
                clock,
                audit);
        mvc = MockMvcBuilders.standaloneSetup(
                        new EndUserObjectEndpoint(objects), new ObjectDownloadEndpoint(downloads))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter())
                .build();
        alice = accounts.provision(TENANT_A, "Alice");
        bob = accounts.provision(TENANT_A, "Bob");
        carolInB = accounts.provision(TENANT_B, "Carol");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadListAndRedeemRoundTrip() throws Exception {
        MvcResult uploaded = mvc.perform(multipart(EndUserObjectEndpoint.PATH)
                        .file(plainFile("note.txt", PAYLOAD))
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerSubjectId").value(alice.subject().subjectId()))
                .andExpect(jsonPath("$.mediaType").value("text/plain"))
                .andExpect(jsonPath("$.sizeBytes").value(PAYLOAD.length))
                .andReturn();
        JsonNode body = mapper.readTree(uploaded.getResponse().getContentAsString());
        String objectKey = body.get("objectKey").asText();
        assertThat(objectKey).startsWith("eu-" + alice.subject().subjectId() + "-");

        mvc.perform(get(EndUserObjectEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects.length()").value(1))
                .andExpect(jsonPath("$.objects[0].objectKey").value(objectKey));

        // Unregistered storage object and a foreign owned object stay off Alice's list.
        // 未登记对象与他人所有对象不出现在 Alice 的列表中。
        storage.put(TENANT_A, "orphan-unregistered", "secret".getBytes(StandardCharsets.UTF_8), "text/plain");
        grants.register(TENANT_A, "bob-owned", bob.subject().subjectId());
        storage.put(TENANT_A, "bob-owned", "bob-file".getBytes(StandardCharsets.UTF_8), "text/plain");

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
                .andExpect(jsonPath("$.backend").value("LOCAL"))
                .andReturn();
        String url = mapper.readTree(issued.getResponse().getContentAsString()).get("url").asText();
        URI uri = URI.create(url);
        mvc.perform(get(uri.getRawPath() + "?" + uri.getRawQuery()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_PLAIN))
                .andExpect(content().bytes(PAYLOAD));

        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND outcome = ?",
                Integer.class,
                ObjectAccessAudit.UPLOAD,
                AuditOutcome.ALLOWED.name());
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void anotherUserCannotListOrDownloadUntilSubjectGranted() throws Exception {
        MvcResult uploaded = mvc.perform(multipart(EndUserObjectEndpoint.PATH)
                        .file(plainFile("note.txt", PAYLOAD))
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andReturn();
        String objectKey = mapper.readTree(uploaded.getResponse().getContentAsString())
                .get("objectKey")
                .asText();

        mvc.perform(get(EndUserObjectEndpoint.PATH)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects.length()").value(0));

        mvc.perform(post(ObjectDownloadEndpoint.END_USER_ISSUE_PATH)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"" + objectKey + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason")
                        .value(com.subjex.platform.app.storage.ObjectDownloadDeniedException.REASON));

        grants.grantToSubject(TENANT_A, objectKey, bob.subject().subjectId(), null, alice.subject().subjectId());

        mvc.perform(get(EndUserObjectEndpoint.PATH)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects.length()").value(1))
                .andExpect(jsonPath("$.objects[0].objectKey").value(objectKey));

        mvc.perform(post(ObjectDownloadEndpoint.END_USER_ISSUE_PATH)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectKey\":\"" + objectKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isNotEmpty());
    }

    @Test
    void oversizeAndDisallowedMimeAreRejected() throws Exception {
        byte[] tooBig = "x".repeat(TEST_CAP + 1).getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart(EndUserObjectEndpoint.PATH)
                        .file(plainFile("big.txt", tooBig))
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.reason").value(ObjectUploadRejectedException.TOO_LARGE));

        mvc.perform(multipart(EndUserObjectEndpoint.PATH)
                        .file(new MockMultipartFile(
                                "file", "a.zip", "application/zip", "pk".getBytes(StandardCharsets.UTF_8)))
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.reason").value(ObjectUploadRejectedException.CONTENT_TYPE));

        mvc.perform(get(EndUserObjectEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects.length()").value(0));
    }

    @Test
    void operatorPlatformAndCrossTenantAreDenied() throws Exception {
        OperatorPrincipal operator =
                new OperatorPrincipal("op-alice", "hash", "op-id", "op-sub", Set.of(), true);
        mvc.perform(multipart(EndUserObjectEndpoint.PATH)
                        .file(plainFile("note.txt", PAYLOAD))
                        .with(asPrincipal(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("end-user-authentication-required"));
        mvc.perform(get(EndUserObjectEndpoint.PATH)
                        .with(asPrincipal(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("end-user-authentication-required"));

        mvc.perform(multipart(EndUserObjectEndpoint.PATH)
                        .file(plainFile("note.txt", PAYLOAD))
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, EndUserAccountStore.RESERVED_TENANT))
                .andExpect(status().isBadRequest());

        mvc.perform(get(EndUserObjectEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(EndUserTenantAccessDeniedException.REASON));

        mvc.perform(get(EndUserObjectEndpoint.PATH)
                        .with(asEndUser(carolInB))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        mvc.perform(get(EndUserObjectEndpoint.PATH).with(asEndUser(alice)))
                .andExpect(status().isForbidden());
    }

    private static MockMultipartFile plainFile(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "text/plain", bytes);
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
