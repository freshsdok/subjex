package com.subjex.platform.app.identity.enduser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.inbox.JdbcInboxPort;
import com.subjex.platform.app.questionnaire.JdbcQuestionnairePort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.storage.JdbcObjectGrantStore;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.app.storage.ObjectDescriptor;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import java.nio.file.Path;
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
 * EndUserProfileFieldsTest — avatar on the authorized-object track and email that must be verified.
 * 头像走授权对象轨；邮箱须验证后才成为资料邮箱。
 */
class EndUserProfileFieldsTest {

    private static final String TENANT_A = "tenant-profile-a";
    private static final String TENANT_B = "tenant-profile-b";
    private static final Instant T0 = Instant.parse("2026-11-26T12:00:00Z");

    /** 1×1 PNG — 一像素 PNG. */
    private static final byte[] PNG = HexFormat.of()
            .parseHex(
                    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000a49444154789c63000100000500010d0a2d0b0000000049454e44ae426082");

    @TempDir
    Path objectRoot;

    private JdbcTemplate jdbc;
    private LocalDirectoryObjectStorage storage;
    private JdbcObjectGrantStore grants;
    private VerificationCodePort codes;
    private MockMvc mvc;
    private EndUserRecord alice;
    private EndUserRecord bob;
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
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        JdbcEndUserAccountStore accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        JdbcInboxPort inbox = new JdbcInboxPort(jdbc, tx);
        RateLimitPort alwaysPermit = (tenantId, actionName) -> true;
        JdbcQuestionnairePort questionnaires = new JdbcQuestionnairePort(jdbc, tx, clock, alwaysPermit);
        storage = new LocalDirectoryObjectStorage(objectRoot);
        EndUserShellFixtures.Kit kit =
                EndUserShellFixtures.shell(accounts, inbox, questionnaires, jdbc, tx, clock, storage);
        grants = kit.grants();
        codes = kit.codes();
        mvc = MockMvcBuilders.standaloneSetup(new EndUserProfileEndpoint(kit.shell()))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
        alice = accounts.provision(TENANT_A, "Alice");
        bob = accounts.provision(TENANT_A, "Bob");
        accounts.provision(TENANT_B, "Carol");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getShowsEmptyAvatarAndEmailAndPatchNameIgnoresTypedEmail() throws Exception {
        mvc.perform(get(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(alice.account().accountId()))
                .andExpect(jsonPath("$.subjectName").value("Alice"))
                .andExpect(jsonPath("$.avatarObjectId").doesNotExist())
                .andExpect(jsonPath("$.avatarDownload").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.pendingEmail").doesNotExist())
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.storageKey").doesNotExist());

        mvc.perform(patch(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"Alice Zhang\",\"email\":\"typed@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectName").value("Alice Zhang"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.pendingEmail").doesNotExist())
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    void ownedImageAvatarCanBeSetAndClearedWithoutExposingStorageKey() throws Exception {
        ObjectDescriptor owned = register(TENANT_A, "alice-avatar", alice.subject().subjectId(), PNG, "image/png");
        ObjectDescriptor bobs = register(TENANT_A, "bob-avatar", bob.subject().subjectId(), PNG, "image/png");
        ObjectDescriptor pdf = register(
                TENANT_A,
                "alice-doc",
                alice.subject().subjectId(),
                "not-an-image".getBytes(),
                "application/pdf");

        MvcResult set = mvc.perform(patch(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarObjectId\":\"" + owned.objectId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectName").value("Alice"))
                .andExpect(jsonPath("$.avatarObjectId").value(owned.objectId()))
                .andExpect(jsonPath("$.avatarDownload.url").isString())
                .andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andReturn();
        JsonNode body = mapper.readTree(set.getResponse().getContentAsString());
        assertThat(body.path("avatarDownload").path("url").asText()).isNotBlank();
        assertThat(body.has("objectKey")).isFalse();
        assertThat(body.has("storageKey")).isFalse();
        assertThat(body.has("avatarTenantId")).isFalse();

        mvc.perform(patch(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarObjectId\":\"" + bobs.objectId() + "\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(patch(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarObjectId\":\"" + pdf.objectId() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("avatar must be an image"));

        mvc.perform(patch(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarObjectId\":\"missing-object\"}"))
                .andExpect(status().isNotFound());

        // Name-only patch must leave the avatar in place — 只改称呼时头像保持不变。
        mvc.perform(patch(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectName\":\"Alice Zhang\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectName").value("Alice Zhang"))
                .andExpect(jsonPath("$.avatarObjectId").value(owned.objectId()))
                .andExpect(jsonPath("$.avatarDownload.url").isString());

        mvc.perform(delete(EndUserProfileEndpoint.AVATAR_PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarObjectId").doesNotExist())
                .andExpect(jsonPath("$.avatarDownload").doesNotExist())
                .andExpect(jsonPath("$.subjectName").value("Alice Zhang"));
    }

    @Test
    void emailStaysPendingUntilVerifiedAndDoesNotReplaceAVerifiedAddressEarly() throws Exception {
        String first = "alice.with.a.longer.address@example.com";
        assertThat(first.length()).isGreaterThan(32);

        mvc.perform(post(EndUserProfileEndpoint.EMAIL_CODE_PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + first + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value(VerificationCodePort.PURPOSE_EMAIL_VERIFY))
                .andExpect(jsonPath("$.devCode").isString());

        mvc.perform(get(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.pendingEmail").value(first))
                .andExpect(jsonPath("$.emailVerified").value(false));

        mvc.perform(post(EndUserProfileEndpoint.EMAIL_VERIFY_PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + first + "\",\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("invalid-verification-code"));

        String firstCode = codes.peekForDev(first, VerificationCodePort.PURPOSE_EMAIL_VERIFY).orElseThrow();
        mvc.perform(post(EndUserProfileEndpoint.EMAIL_VERIFY_PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + first + "\",\"code\":\"" + firstCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(first))
                .andExpect(jsonPath("$.pendingEmail").doesNotExist())
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.emailVerifiedAt").isString());

        String second = "alice.new@example.com";
        mvc.perform(post(EndUserProfileEndpoint.EMAIL_CODE_PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + second + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(get(EndUserProfileEndpoint.PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(first))
                .andExpect(jsonPath("$.pendingEmail").value(second))
                .andExpect(jsonPath("$.emailVerified").value(true));

        mvc.perform(post(EndUserProfileEndpoint.EMAIL_CODE_PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + first + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("email is already verified"));

        String secondCode = codes.peekForDev(second, VerificationCodePort.PURPOSE_EMAIL_VERIFY).orElseThrow();
        mvc.perform(post(EndUserProfileEndpoint.EMAIL_VERIFY_PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + second + "\",\"code\":\"" + secondCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(second))
                .andExpect(jsonPath("$.pendingEmail").doesNotExist())
                .andExpect(jsonPath("$.emailVerified").value(true));
    }

    @Test
    void operatorAndCrossTenantAreRejected() throws Exception {
        OperatorPrincipal operator =
                new OperatorPrincipal("op-alice", "hash", "op-id", "op-sub", Set.of(), true);
        mvc.perform(get(EndUserProfileEndpoint.PATH)
                        .with(asPrincipal(operator))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("end-user-authentication-required"));

        mvc.perform(post(EndUserProfileEndpoint.EMAIL_CODE_PATH)
                        .with(asEndUser(alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(EndUserTenantAccessDeniedException.REASON));
    }

    private ObjectDescriptor register(
            String tenantId, String objectKey, String ownerSubjectId, byte[] content, String mediaType) {
        storage.put(tenantId, objectKey, content, mediaType);
        return grants.register(tenantId, objectKey, ownerSubjectId);
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
