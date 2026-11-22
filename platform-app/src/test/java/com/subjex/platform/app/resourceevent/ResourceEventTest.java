package com.subjex.platform.app.resourceevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.ratelimit.SingleProcessRateLimit;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantNotGrantedException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ResourceEventTest — only recorded share/open facts; no invented counts or last-click times.
 * 只汇总已经记下的分享/打开；不编造数字或最后点击时间。
 */
class ResourceEventTest {

    private static final Instant NOW = Instant.parse("2026-11-23T04:00:00Z");
    private static final String TENANT_A = "tenant-share-a";
    private static final String TENANT_B = "tenant-share-b";

    private JdbcTemplate jdbc;
    private ResourceEventService service;
    private JdbcEndUserAccountStore accounts;
    private MockMvc mvc;
    private OperatorPrincipal admin;
    private EndUserPrincipal pat;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
        service = new ResourceEventService(
                jdbc,
                new JdbcResourceEventStore(jdbc),
                new JdbcOperatorTenantAccess(jdbc, tx),
                new SingleProcessRateLimit(100, Duration.ofMinutes(1), clock),
                clock);
        mvc = MockMvcBuilders.standaloneSetup(new ResourceEventEndpoint(service))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
        jdbc.update("INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, 'A', 'ACTIVE')", TENANT_A);
        jdbc.update("INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, 'B', 'ACTIVE')", TENANT_B);
        admin = new OperatorPrincipal(
                "share-admin", "hash", "id-share-admin", "subject-share-admin", Set.of("resource-event.read"), true);
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)", admin.subjectId(), TENANT_A);
        var record = accounts.provision(TENANT_A, "Pat Lee");
        pat = new EndUserPrincipal(record.account().accountId(), record.subject().subjectId(), "eu:pat", true);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsShareAndOpenAndSummarisesOnlyThisTenant() throws Exception {
        mvc.perform(post(ResourceEventEndpoint.PATH)
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"resourceType":"questionnaire","resourceId":"q-1","eventType":"share",
                                 "campaign":"spring-hello","utmSource":"wechat"}
                                """)
                        .with(asPrincipal(pat)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("share"))
                .andExpect(jsonPath("$.occurredAt").value(NOW.toString()));
        mvc.perform(post(ResourceEventEndpoint.PATH)
                        .header("X-Tenant-Id", TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"questionnaire\",\"resourceId\":\"q-1\",\"eventType\":\"view\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("open"));
        mvc.perform(post(ResourceEventEndpoint.PATH)
                        .header("X-Tenant-Id", TENANT_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"questionnaire\",\"resourceId\":\"q-other\",\"eventType\":\"open\"}"))
                .andExpect(status().isOk());

        mvc.perform(get(ResourceEventEndpoint.SUMMARY_PATH)
                        .queryParam("tenantId", TENANT_A)
                        .with(asPrincipal(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.from").value(NOW.minus(Duration.ofDays(30)).toString()))
                .andExpect(jsonPath("$.to").value(NOW.toString()))
                .andExpect(jsonPath("$.totals.shares").value(1))
                .andExpect(jsonPath("$.totals.opens").value(1))
                .andExpect(jsonPath("$.resources.length()").value(1))
                .andExpect(jsonPath("$.resources[0].resourceId").value("q-1"))
                .andExpect(jsonPath("$.resources[0].shares").value(1))
                .andExpect(jsonPath("$.resources[0].opens").value(1))
                .andExpect(jsonPath("$.daily.length()").value(1))
                .andExpect(jsonPath("$.daily[0].day").value("2026-11-23"))
                .andExpect(jsonPath("$.daily[0].opens").value(1))
                .andExpect(jsonPath("$.sharers.length()").value(1))
                .andExpect(jsonPath("$.sharers[0].displayName").value("Pat Lee"))
                .andExpect(jsonPath("$.sharers[0].actorSubjectId").value(pat.subjectId()))
                .andExpect(jsonPath("$.campaigns.length()").value(1))
                .andExpect(jsonPath("$.campaigns[0].campaign").value("spring-hello"))
                .andExpect(jsonPath("$.campaigns[0].utmSource").value("wechat"))
                .andExpect(jsonPath("$.campaigns[0].shares").value(1))
                .andExpect(jsonPath("$.campaigns[0].opens").value(0));
    }

    @Test
    void emptyTenantShowsZeroesAndNoCampaignRows() throws Exception {
        mvc.perform(get(ResourceEventEndpoint.SUMMARY_PATH)
                        .queryParam("tenantId", TENANT_A)
                        .with(asPrincipal(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.shares").value(0))
                .andExpect(jsonPath("$.totals.opens").value(0))
                .andExpect(jsonPath("$.resources.length()").value(0))
                .andExpect(jsonPath("$.daily.length()").value(0))
                .andExpect(jsonPath("$.sharers.length()").value(0))
                .andExpect(jsonPath("$.campaigns.length()").value(0));
    }

    @Test
    void anonymousShareHasNoActorAndUnknownTenantIsRefused() {
        var accepted = service.record(
                null,
                TENANT_A,
                new ResourceEventService.IngestRequest(
                        null, "questionnaire", "q-2", "share", null, null, null, null, null, null, null));
        assertThat(accepted.eventType()).isEqualTo("share");
        var summary = service.summarise(admin, TENANT_A, NOW.minusSeconds(60), NOW.plusSeconds(1));
        assertThat(summary.sharers()).hasSize(1);
        assertThat(summary.sharers().get(0).actorSubjectId()).isNull();
        assertThat(summary.campaigns()).isEmpty();

        assertThatThrownBy(() -> service.record(
                        null,
                        "missing-tenant",
                        new ResourceEventService.IngestRequest(
                                null, "questionnaire", "q-2", "open", null, null, null, null, null, null, null)))
                .isInstanceOf(TenantNotFoundException.class);
        assertThatThrownBy(() -> service.record(
                        null,
                        "platform",
                        new ResourceEventService.IngestRequest(
                                null, "questionnaire", "q-2", "open", null, null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summarise(admin, TENANT_B, null, null))
                .isInstanceOf(OperatorTenantNotGrantedException.class);
        assertThatThrownBy(() -> service.summarise(admin, " ", null, null))
                .isInstanceOf(TenantMissingException.class);
    }

    @Test
    void unauthenticatedSummaryIsUnauthorized() throws Exception {
        mvc.perform(get(ResourceEventEndpoint.SUMMARY_PATH).queryParam("tenantId", TENANT_A))
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
