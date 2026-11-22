package com.subjex.platform.app.identity.enduser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantNotGrantedException;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * EndUserDirectoryTest — list only existing tenant members; mask secrets; no invented last-login.
 * 只列出该租户已有成员；遮盖敏感字段；不编造最后登录时间。
 */
class EndUserDirectoryTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T01:00:00Z"), ZoneOffset.UTC);
    private static final String TENANT_A = "tenant-dir-a";
    private static final String TENANT_B = "tenant-dir-b";
    private static final String OPEN_ID = "oHiddenOpenIdABCDEF123456";

    private JdbcTemplate jdbc;
    private EndUserAccountStore accounts;
    private EndUserDirectoryService directory;
    private MockMvc mvc;
    private OperatorPrincipal admin;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        accounts = new JdbcEndUserAccountStore(jdbc, tx, CLOCK);
        directory = new EndUserDirectoryService(jdbc, new JdbcOperatorTenantAccess(jdbc, tx));
        mvc = MockMvcBuilders.standaloneSetup(new EndUserDirectoryEndpoint(directory))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, 'A', 'ACTIVE')", TENANT_A);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, 'B', 'ACTIVE')", TENANT_B);
        admin = new OperatorPrincipal(
                "dir-admin", "hash", LocalOperatorSeeder.IDENTITY_ID, "subject-dir-admin", Set.of("end-user.read"), true);
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                admin.subjectId(),
                TENANT_A);

        EndUserRecord pat = accounts.provision(TENANT_A, "Pat Lee");
        accounts.bindPhone(pat.account().accountId(), "13812345678");
        accounts.bindIdp(pat.account().accountId(), "wechat-mini", "wechat:mini", OPEN_ID);
        accounts.provision(TENANT_A, "Alex Ng");
        accounts.provision(TENANT_B, "Other User");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsOnlyGrantedTenantAndMasksBindings() throws Exception {
        var result = mvc.perform(get(EndUserDirectoryEndpoint.PATH)
                        .queryParam("tenantId", TENANT_A)
                        .with(asPrincipal(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.users.length()").value(2))
                .andExpect(jsonPath("$.users[0].displayName").value("Alex Ng"))
                .andExpect(jsonPath("$.users[1].displayName").value("Pat Lee"))
                .andExpect(jsonPath("$.users[1].accountState").value("ACTIVE"))
                .andExpect(jsonPath("$.users[1].membershipState").value("ACTIVE"))
                .andExpect(jsonPath("$.users[1].phoneMasked").value("138****5678"))
                .andExpect(jsonPath("$.users[1].idp.issuer").value("wechat:mini"))
                .andExpect(jsonPath("$.users[1].idp.providerId").value("wechat-mini"))
                .andExpect(jsonPath("$.users[1].idp.idpSubject").doesNotExist())
                .andExpect(jsonPath("$.users[0].phoneMasked").isEmpty())
                .andExpect(jsonPath("$.users[0].idp").isEmpty())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("token_hash", "tokenhash", OPEN_ID.toLowerCase(Locale.ROOT));
        assertThat(body).doesNotContain("13812345678");
        assertThat(body).doesNotContain("lastLogin", "lastUsed", "last_login");
    }

    @Test
    void lookupFindsByNameSubjectAndFullPhoneWithoutLeakingTheNumber() throws Exception {
        mvc.perform(get(EndUserDirectoryEndpoint.PATH)
                        .queryParam("tenantId", TENANT_A)
                        .queryParam("q", "pat")
                        .with(asPrincipal(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].displayName").value("Pat Lee"));
        EndUserRecord pat = accounts.findByPhone("13812345678").orElseThrow();
        mvc.perform(get(EndUserDirectoryEndpoint.PATH)
                        .queryParam("tenantId", TENANT_A)
                        .queryParam("q", pat.subject().subjectId())
                        .with(asPrincipal(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].subjectId").value(pat.subject().subjectId()));
        mvc.perform(get(EndUserDirectoryEndpoint.PATH)
                        .queryParam("tenantId", TENANT_A)
                        .queryParam("q", "13812345678")
                        .with(asPrincipal(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.users[0].phoneMasked").value("138****5678"))
                .andExpect(jsonPath("$.users[0].displayName").value("Pat Lee"));
    }

    @Test
    void ungrantedOrReservedTenantIsRefused() {
        assertThatThrownBy(() -> directory.list(admin, TENANT_B, null, null))
                .isInstanceOf(OperatorTenantNotGrantedException.class);
        assertThatThrownBy(() -> directory.list(admin, "platform", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform");
        assertThatThrownBy(() -> directory.list(admin, "  ", null, null))
                .isInstanceOf(TenantMissingException.class);
    }

    @Test
    void maskPhoneKeepsARecognizablePrefixAndSuffix() {
        assertThat(EndUserDirectoryService.maskPhone("13812345678")).isEqualTo("138****5678");
        assertThat(EndUserDirectoryService.maskPhone("12345")).isEqualTo("12*45");
        assertThat(EndUserDirectoryService.maskPhone("ab")).isEqualTo("**");
        assertThat(EndUserDirectoryService.maskPhone(null)).isNull();
    }

    @Test
    void unauthenticatedIsUnauthorized() throws Exception {
        mvc.perform(get(EndUserDirectoryEndpoint.PATH).queryParam("tenantId", TENANT_A))
                .andExpect(status().isUnauthorized());
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
