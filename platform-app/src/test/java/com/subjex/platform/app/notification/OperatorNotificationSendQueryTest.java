package com.subjex.platform.app.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.PolicyEngineFactory;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationCommand;
import com.subjex.platform.contract.notification.NotificationRecipient;
import com.subjex.platform.contract.notification.NotificationReceipt;
import com.subjex.platform.contract.task.OriginKind;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
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
import tools.jackson.databind.ObjectMapper;

/**
 * OperatorNotificationSendQueryTest — operator read-only send-log list/get, filters, isolation, no write routes.
 * 操作员只读通知发送记录列表与详情、筛选、租户隔离；不能发送、重试或重放。
 */
class OperatorNotificationSendQueryTest {

    private static final String TENANT_A = "tenant-notify-ui-a";
    private static final String TENANT_B = "tenant-notify-ui-b";
    private static final Instant T0 = Instant.parse("2026-11-22T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-22T10:00:00Z");
    private static final String DEST_ADA = "ada@example.test";
    private static final String DEST_SMS = "+8613800000000";
    private static final String OTHER_EVENT_ID = "event-task-recorded-a";
    private static final String LIVE_LETTER_ID = "dl-notify-sms";
    private static final String DISCARDED_LETTER_ID = "dl-notify-sms-old";

    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private OperatorPrincipal reader;
    private OperatorPrincipal none;
    private NotificationReceipt emailSend;
    private NotificationReceipt smsSend;
    private NotificationReceipt otherTenant;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

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
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        ObjectMapper mapper = new ObjectMapper();
        JdbcNotificationPort port = new JdbcNotificationPort(jdbc, mapper, clock);
        JdbcOperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        OperatorNotificationService service = new OperatorNotificationService(
                new JdbcNotificationSendQuery(jdbc, mapper),
                tenantAccess,
                PolicyEngineFactory.create("sql", new DenyWhenTenantMissing(), tenantAccess));
        mvc = MockMvcBuilders.standaloneSetup(new OperatorNotificationEndpoint(service))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();

        reader = operator("reader", "notification.read");
        none = operator("none");
        grant(reader, TENANT_A);
        grant(none, TENANT_A);

        now.set(T0);
        emailSend = port.enqueue(command(TENANT_A, "order.approved", NotificationChannel.EMAIL, DEST_ADA));
        now.set(T1);
        smsSend = port.enqueue(command(TENANT_A, "payment.failed", NotificationChannel.SMS, DEST_SMS));
        otherTenant = port.enqueue(command(TENANT_B, "order.approved", NotificationChannel.EMAIL, DEST_ADA));

        jdbc.update(
                "UPDATE outbox_event SET event_state = ?, attempt_count = ?, failure_reason = ? WHERE event_id = ?",
                OutboxState.DEAD.name(),
                3,
                "adapter failed",
                smsSend.notificationId());
        jdbc.update(
                "INSERT INTO dead_letter (dead_letter_id, tenant_id, origin_kind, origin_id, failure_reason, recorded_at, discarded_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                DISCARDED_LETTER_ID,
                TENANT_A,
                OriginKind.OUTBOX_EVENT.name(),
                smsSend.notificationId(),
                "old failure",
                Timestamp.from(T0),
                Timestamp.from(T1));
        jdbc.update(
                "INSERT INTO dead_letter (dead_letter_id, tenant_id, origin_kind, origin_id, failure_reason, recorded_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                LIVE_LETTER_ID,
                TENANT_A,
                OriginKind.OUTBOX_EVENT.name(),
                smsSend.notificationId(),
                "adapter failed",
                Timestamp.from(T1));
        jdbc.update(
                "INSERT INTO outbox_event (event_id, tenant_id, event_name, event_body, event_state, trace_id, "
                        + "attempt_count, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                OTHER_EVENT_ID,
                TENANT_A,
                "TaskRecorded",
                "{\"taskId\":\"t-1\"}",
                OutboxState.PENDING.name(),
                "trace-other",
                0,
                Timestamp.from(T1));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listIsNewestFirstAndHidesOtherTenantAndNonNotificationEvents() throws Exception {
        mvc.perform(get(OperatorNotificationEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventId").value(smsSend.notificationId()))
                .andExpect(jsonPath("$[0].templateId").value("payment.failed"))
                .andExpect(jsonPath("$[0].channel").value(NotificationChannel.SMS.name()))
                .andExpect(jsonPath("$[0].recipientDestination").value(DEST_SMS))
                .andExpect(jsonPath("$[0].status").value(OutboxState.DEAD.name()))
                .andExpect(jsonPath("$[0].attemptCount").value(3))
                .andExpect(jsonPath("$[0].failureReason").value("adapter failed"))
                .andExpect(jsonPath("$[0].deadLetterId").value(LIVE_LETTER_ID))
                .andExpect(jsonPath("$[1].eventId").value(emailSend.notificationId()))
                .andExpect(jsonPath("$[1].channel").value(NotificationChannel.EMAIL.name()))
                .andExpect(jsonPath("$[1].status").value(OutboxState.PENDING.name()))
                .andExpect(jsonPath("$[1].deadLetterId").doesNotExist());
    }

    @Test
    void listFiltersByStatusChannelRecipientAndTimeWindow() throws Exception {
        mvc.perform(get(OperatorNotificationEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("status", "DEAD")
                        .param("channel", "SMS")
                        .param("recipient", DEST_SMS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value(smsSend.notificationId()));

        mvc.perform(get(OperatorNotificationEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("from", T1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value(smsSend.notificationId()));

        mvc.perform(get(OperatorNotificationEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("to", T0.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value(emailSend.notificationId()));

        mvc.perform(get(OperatorNotificationEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest());

        mvc.perform(get(OperatorNotificationEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("channel", "FAX"))
                .andExpect(status().isBadRequest());

        mvc.perform(get(OperatorNotificationEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .param("recipient", "ada%"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturnsDeadLetterAndUnknownOrOtherEventTypesAreNotFound() throws Exception {
        mvc.perform(get(OperatorNotificationEndpoint.PATH + "/" + smsSend.notificationId())
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(smsSend.notificationId()))
                .andExpect(jsonPath("$.tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.templateId").value("payment.failed"))
                .andExpect(jsonPath("$.parameters.orderId").value("ORD-41"))
                .andExpect(jsonPath("$.status").value(OutboxState.DEAD.name()))
                .andExpect(jsonPath("$.deadLetterId").value(LIVE_LETTER_ID));

        mvc.perform(get(OperatorNotificationEndpoint.PATH + "/missing-send")
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value(NotificationSendNotFoundException.REASON));

        mvc.perform(get(OperatorNotificationEndpoint.PATH + "/" + OTHER_EVENT_ID)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value(NotificationSendNotFoundException.REASON));
    }

    @Test
    void missingPermissionOtherTenantAndCrossTenantGetAreDenied() throws Exception {
        mvc.perform(get(OperatorNotificationEndpoint.PATH)
                        .with(asOperator(none))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        mvc.perform(get(OperatorNotificationEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());

        mvc.perform(get(OperatorNotificationEndpoint.PATH + "/" + otherTenant.notificationId())
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(NotificationSendCrossTenantDeniedException.REASON));
    }

    @Test
    void reservedOrMissingTenantIsRejectedAndWriteRouteDoesNotExist() throws Exception {
        mvc.perform(get(OperatorNotificationEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, "platform"))
                .andExpect(status().isForbidden());

        mvc.perform(get(OperatorNotificationEndpoint.PATH).with(asOperator(reader)))
                .andExpect(status().isForbidden());

        mvc.perform(post(OperatorNotificationEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType("application/json")
                        .content("{\"templateId\":\"order.approved\",\"channel\":\"SMS\"}"))
                .andExpect(status().isMethodNotAllowed());
    }

    private void grant(OperatorPrincipal operator, String tenantId) {
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                operator.subjectId(),
                tenantId);
    }

    private static NotificationCommand command(
            String tenantId, String templateId, NotificationChannel channel, String destination) {
        return new NotificationCommand(
                tenantId,
                templateId,
                channel,
                NotificationRecipient.ofDestination(destination),
                Map.of("orderId", "ORD-41"));
    }

    private static OperatorPrincipal operator(String slug, String... permissions) {
        return new OperatorPrincipal(
                "op-" + slug, "hash", "id-" + slug, "sub-" + slug, Set.of(permissions), true);
    }

    private static RequestPostProcessor asOperator(OperatorPrincipal operator) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(operator, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }
}
