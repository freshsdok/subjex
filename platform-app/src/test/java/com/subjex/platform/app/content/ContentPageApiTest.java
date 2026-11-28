package com.subjex.platform.app.content;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.EndUserRealmGateFilter;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.JdbcTenantAdmin;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.PolicyEngineFactory;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.app.tenant.TenantHostResolutionFilter;
import com.subjex.platform.app.tenant.TenantHostService;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.content.ContentPageNotFoundException;
import com.subjex.platform.contract.content.ContentPageSlugConflictException;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import java.nio.file.Files;
import java.sql.Timestamp;
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
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
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
 * ContentPageApiTest — operator CRUD/publish, public published-only read, tenant isolation, slug uniqueness.
 * 操作员增改与发布、公开仅可读已发布页、租户隔离与别名唯一。
 */
class ContentPageApiTest {

    private static final String TENANT_A = "tenant-content-a";
    private static final String TENANT_B = "tenant-content-b";
    private static final Instant T0 = Instant.parse("2026-11-28T12:00:00Z");
    private static final String HOST_A = "pages.acme.test";

    private JdbcTemplate jdbc;
    private JdbcContentPagePort port;
    private LocalDirectoryObjectStorage storage;
    private MockMvc operatorMvc;
    private MockMvc publicMvc;
    private OperatorPrincipal reader;
    private OperatorPrincipal writer;
    private String objectId;

    @BeforeEach
    void setUp() throws Exception {
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
        JdbcOperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        port = new JdbcContentPagePort(jdbc, tx, clock);
        storage = new LocalDirectoryObjectStorage(Files.createTempDirectory("content-pages-media"));
        OperatorContentPageService operatorService = new OperatorContentPageService(
                port, tenantAccess, PolicyEngineFactory.create("sql", new DenyWhenTenantMissing(), tenantAccess));
        PublicContentPageService publicService = new PublicContentPageService(port, storage);
        TenantHostService hosts = new TenantHostService(
                jdbc,
                new JdbcTenantAdmin(jdbc),
                new OperatorActionAudit(new JdbcAuditPort(jdbc), clock),
                clock,
                "localhost,127.0.0.1");
        operatorMvc = MockMvcBuilders.standaloneSetup(new OperatorContentPageEndpoint(operatorService))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
        publicMvc = MockMvcBuilders.standaloneSetup(new PublicContentPageEndpoint(publicService))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(), new ByteArrayHttpMessageConverter())
                .addFilters(new TenantHostResolutionFilter(hosts), new EndUserRealmGateFilter())
                .build();

        reader = operator("reader", "content.read");
        writer = operator("writer", "content.read", "content.write");
        grant(reader, TENANT_A);
        grant(writer, TENANT_A);
        grant(writer, TENANT_B);
        hosts.bind(writer, TENANT_A, HOST_A);

        objectId = "obj-welcome";
        jdbc.update(
                "INSERT INTO object_descriptor (object_id, tenant_id, object_key, owner_subject_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                objectId,
                TENANT_A,
                "welcome.png",
                writer.subjectId(),
                Timestamp.from(T0));
        storage.put(TENANT_A, "welcome.png", new byte[] {1, 2, 3, 4}, "image/png");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void operatorCreatesEditsPublishesAndUnpublishes() throws Exception {
        String created = operatorMvc.perform(post(OperatorContentPageEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("about", "About us", titleBlock("Hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("about"))
                .andExpect(jsonPath("$.title").value("About us"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.blocks[0].type").value("TITLE"))
                .andExpect(jsonPath("$.blocks[0].text").value("Hello"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String pageId = readJson(created, "pageId");

        operatorMvc.perform(put(OperatorContentPageEndpoint.PATH + "/" + pageId)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody(
                                "about",
                                "About us",
                                titleBlock("Hello"),
                                textBlock("Welcome", "MARKDOWN"),
                                imageBlock(objectId, "Team"),
                                linkBlock("Home", "https://example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.blocks.length()").value(4))
                .andExpect(jsonPath("$.blocks[2].objectId").value(objectId));

        operatorMvc.perform(post(OperatorContentPageEndpoint.PATH + "/" + pageId + "/publish")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").exists());

        operatorMvc.perform(get(OperatorContentPageEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].slug").value("about"));

        operatorMvc.perform(post(OperatorContentPageEndpoint.PATH + "/" + pageId + "/unpublish")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void slugIsUniqueInATenantAndOperatorPermissionIsEnforced() throws Exception {
        operatorMvc.perform(post(OperatorContentPageEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("about", "One", titleBlock("A"))))
                .andExpect(status().isOk());
        operatorMvc.perform(post(OperatorContentPageEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("about", "Two", titleBlock("B"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value(ContentPageSlugConflictException.REASON));
        operatorMvc.perform(post(OperatorContentPageEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("about", "Other tenant", titleBlock("C"))))
                .andExpect(status().isOk());

        operatorMvc.perform(get(OperatorContentPageEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk());
        operatorMvc.perform(post(OperatorContentPageEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pageBody("denied", "Denied", titleBlock("X"))))
                .andExpect(status().isForbidden());
        operatorMvc.perform(get(OperatorContentPageEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());
        operatorMvc.perform(get(OperatorContentPageEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, JdbcContentPagePort.RESERVED_TENANT))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicPathReadsPublishedOnlyAndHidesDraftsAndOtherTenants() throws Exception {
        String pageId = readJson(
                operatorMvc.perform(post(OperatorContentPageEndpoint.PATH)
                                .with(asOperator(writer))
                                .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(pageBody(
                                        "about",
                                        "About us",
                                        titleBlock("Hello"),
                                        imageBlock(objectId, "Team"))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "pageId");

        publicMvc.perform(get(PublicContentPageEndpoint.PATH + "/about")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value(ContentPageNotFoundException.REASON));
        publicMvc.perform(get(PublicContentPageEndpoint.PATH + "/about/media/" + objectId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());

        operatorMvc.perform(post(OperatorContentPageEndpoint.PATH + "/" + pageId + "/publish")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk());

        publicMvc.perform(get(PublicContentPageEndpoint.PATH)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].slug").value("about"))
                .andExpect(jsonPath("$[0].title").value("About us"));
        publicMvc.perform(get(PublicContentPageEndpoint.PATH + "/about")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.blocks[1].objectId").value(objectId))
                .andExpect(jsonPath("$.blocks[1].mediaUrl").exists());
        publicMvc.perform(get(PublicContentPageEndpoint.PATH + "/about")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isNotFound());
        publicMvc.perform(get(PublicContentPageEndpoint.PATH)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        publicMvc.perform(get(PublicContentPageEndpoint.PATH + "/about")
                        .header(TenantEnforcementFilter.TENANT_HEADER, JdbcContentPagePort.RESERVED_TENANT))
                .andExpect(status().isForbidden());
        publicMvc.perform(get(PublicContentPageEndpoint.PATH + "/about"))
                .andExpect(status().isForbidden());

        byte[] bytes = publicMvc.perform(get(PublicContentPageEndpoint.PATH + "/about/media/" + objectId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertArrayEquals(new byte[] {1, 2, 3, 4}, bytes);
        publicMvc.perform(get(PublicContentPageEndpoint.PATH + "/about/media/no-such")
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());
        publicMvc.perform(get(PublicContentPageEndpoint.PATH + "/about/media/" + objectId)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isNotFound());

        publicMvc.perform(get(PublicContentPageEndpoint.PATH + "/about").header("Host", HOST_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("about"));

        EndUserPrincipal endUser = new EndUserPrincipal("acc-ada", "sub-ada", "ada", true);
        publicMvc.perform(get(PublicContentPageEndpoint.PATH + "/about")
                        .with(asPrincipal(endUser))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("about"));
    }

    private void grant(OperatorPrincipal operator, String tenantId) {
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                operator.subjectId(),
                tenantId);
    }

    private static String pageBody(String slug, String title, String... blocks) {
        return "{\"slug\":\"" + slug + "\",\"title\":\"" + title + "\",\"blocks\":[" + String.join(",", blocks) + "]}";
    }

    private static String titleBlock(String text) {
        return "{\"type\":\"TITLE\",\"text\":\"" + text + "\"}";
    }

    private static String textBlock(String text, String format) {
        return "{\"type\":\"TEXT\",\"text\":\"" + text + "\",\"format\":\"" + format + "\"}";
    }

    private static String imageBlock(String objectId, String alt) {
        return "{\"type\":\"IMAGE\",\"objectId\":\"" + objectId + "\",\"alt\":\"" + alt + "\"}";
    }

    private static String linkBlock(String label, String href) {
        return "{\"type\":\"LINK\",\"text\":\"" + label + "\",\"href\":\"" + href + "\"}";
    }

    private static String readJson(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            throw new IllegalStateException("missing " + field + " in " + json);
        }
        int start = at + needle.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private static OperatorPrincipal operator(String slug, String... permissions) {
        return new OperatorPrincipal(
                "op-" + slug, "hash", "id-" + slug, "sub-" + slug, Set.of(permissions), true);
    }

    private static RequestPostProcessor asOperator(OperatorPrincipal operator) {
        return asPrincipal(operator);
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
