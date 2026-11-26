package com.subjex.platform.app.identity.enduser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.inbox.EndUserShellInboxEndpoint;
import com.subjex.platform.app.inbox.JdbcInboxPort;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.inbox.InboxAttachmentBind;
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxRecipient;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.inbox.InboxSendRequest;
import com.subjex.platform.contract.inbox.InboxSendResult;
import com.subjex.platform.contract.inbox.InboxSeverity;
import com.subjex.platform.contract.questionnaire.QuestionnairePort;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import com.subjex.platform.app.questionnaire.JdbcQuestionnairePort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
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
 * EndUserInboxAttachmentTest — end users list and download attachments on their own inbox entries.
 * Another subject, a foreign entry, or a missing attachment is 404. Bytes are returned directly
 * (not a signed ObjectGrant URL). No end-user compose/send.
 * <p>
 * 终端用户可列出并直接下载本人收件上的附件；他人条目或缺失附件为 404。不是签名链接。不做终端写信。
 */
class EndUserInboxAttachmentTest {

    private static final String TENANT_A = "tenant-eu-inbox-att-a";
    private static final String TENANT_B = "tenant-eu-inbox-att-b";
    private static final Instant T0 = Instant.parse("2026-11-26T08:00:00Z");
    private static final byte[] PAYLOAD = "hello-end-user-inbox".getBytes(StandardCharsets.UTF_8);

    private MockMvc mvc;
    private LocalDirectoryObjectStorage storage;
    private JdbcInboxPort inbox;
    private EndUserRecord alice;
    private EndUserRecord bob;
    private EndUserRecord carolInB;
    private String sharedAttachmentId;
    private String aliceEntryId;
    private String bobEntryId;
    private String alicePlainEntryId;
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path objectRoot;

    @BeforeEach
    void setUp() throws Exception {
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
        QuestionnairePort questionnaires = new JdbcQuestionnairePort(jdbc, tx, clock, alwaysPermit);
        storage = new LocalDirectoryObjectStorage(objectRoot);
        EndUserShellService shell = EndUserShellFixtures.shell(
                accounts, inbox, questionnaires, jdbc, tx, clock, storage).shell();
        mvc = MockMvcBuilders.standaloneSetup(new EndUserShellInboxEndpoint(shell))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter())
                .build();

        alice = accounts.provision(TENANT_A, "Alice");
        bob = accounts.provision(TENANT_A, "Bob");
        carolInB = accounts.provision(TENANT_B, "Carol");

        String storageKey = "inbox-att-shared";
        storage.put(TENANT_A, storageKey, PAYLOAD, "text/plain");
        InboxSendResult shared = inbox.send(new InboxSendRequest(
                TENANT_A,
                InboxChannel.SYSTEM,
                "带附件",
                "请查收",
                InboxSeverity.INFO,
                "system",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(
                        new InboxRecipient(InboxRecipientRealm.END_USER, alice.subject().subjectId()),
                        new InboxRecipient(InboxRecipientRealm.END_USER, bob.subject().subjectId())),
                List.of(new InboxAttachmentBind(
                        "hello.txt",
                        "text/plain",
                        PAYLOAD.length,
                        storageKey,
                        sha256Hex(PAYLOAD),
                        0)),
                T0));
        sharedAttachmentId = shared.attachments().get(0).attachmentId();
        aliceEntryId = entryIdFor(shared, alice.subject().subjectId());
        bobEntryId = entryIdFor(shared, bob.subject().subjectId());

        InboxSendResult plain = inbox.send(new InboxSendRequest(
                TENANT_A,
                InboxChannel.SYSTEM,
                "无附件",
                "纯文字",
                InboxSeverity.INFO,
                "system",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(new InboxRecipient(InboxRecipientRealm.END_USER, alice.subject().subjectId())),
                T0));
        alicePlainEntryId = plain.entries().get(0).entryId();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownEntryListsAndDownloadsSharedAttachment() throws Exception {
        MvcResult listed = mvc.perform(get(EndUserShellInboxEndpoint.BASE
                                + "/entries/"
                                + aliceEntryId
                                + "/attachments")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments.length()").value(1))
                .andExpect(jsonPath("$.attachments[0].attachmentId").value(sharedAttachmentId))
                .andExpect(jsonPath("$.attachments[0].fileName").value("hello.txt"))
                .andExpect(jsonPath("$.attachments[0].contentType").value("text/plain"))
                .andExpect(jsonPath("$.attachments[0].byteSize").value(PAYLOAD.length))
                .andExpect(jsonPath("$.attachments[0].storageKey").doesNotExist())
                .andReturn();
        JsonNode body = mapper.readTree(listed.getResponse().getContentAsString());
        assertThat(body.toString()).doesNotContain("inbox-att-shared");

        mvc.perform(get(EndUserShellInboxEndpoint.BASE
                                + "/entries/"
                                + aliceEntryId
                                + "/attachments/"
                                + sharedAttachmentId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"hello.txt\""))
                .andExpect(content().contentType(MediaType.TEXT_PLAIN))
                .andExpect(content().bytes(PAYLOAD));

        // Same message, Bob uses his own entry — 同一封信，Bob 走自己的收件条目。
        mvc.perform(get(EndUserShellInboxEndpoint.BASE
                                + "/entries/"
                                + bobEntryId
                                + "/attachments/"
                                + sharedAttachmentId)
                        .with(asEndUser(bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PAYLOAD));

        mvc.perform(get(EndUserShellInboxEndpoint.BASE
                                + "/entries/"
                                + alicePlainEntryId
                                + "/attachments")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachments.length()").value(0));
    }

    @Test
    void foreignEntryUnknownAttachmentAndOtherRealmAreHidden() throws Exception {
        // Alice cannot use Bob's entry even for a message she also received.
        // Alice 不能借用 Bob 的收件条目，即使两人收到同一封信。
        mvc.perform(get(EndUserShellInboxEndpoint.BASE + "/entries/" + bobEntryId + "/attachments")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());
        mvc.perform(get(EndUserShellInboxEndpoint.BASE
                                + "/entries/"
                                + bobEntryId
                                + "/attachments/"
                                + sharedAttachmentId)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());

        mvc.perform(get(EndUserShellInboxEndpoint.BASE
                                + "/entries/"
                                + aliceEntryId
                                + "/attachments/missing-attachment")
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());

        OperatorPrincipal operator =
                new OperatorPrincipal("op-alice", "hash", "op-id", "op-sub", Set.of(), true);
        mvc.perform(get(EndUserShellInboxEndpoint.BASE + "/entries/" + aliceEntryId + "/attachments")
                        .with(asPrincipal(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("end-user-authentication-required"));

        mvc.perform(get(EndUserShellInboxEndpoint.BASE + "/entries/" + aliceEntryId + "/attachments")
                        .with(asEndUser(carolInB))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(EndUserTenantAccessDeniedException.REASON));

        mvc.perform(get(EndUserShellInboxEndpoint.BASE + "/entries/" + aliceEntryId + "/attachments")
                        .with(asEndUser(alice)))
                .andExpect(status().isForbidden());
    }

    private static String entryIdFor(InboxSendResult sent, String subjectId) {
        return sent.entries().stream()
                .filter(entry -> subjectId.equals(entry.recipientSubjectId()))
                .findFirst()
                .orElseThrow()
                .entryId();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return HexFormat.of().formatHex(digest);
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
