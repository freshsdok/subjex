package com.subjex.platform.app.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.lock.SingleProcessLock;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationCommand;
import com.subjex.platform.contract.notification.NotificationCrossTenantDeniedException;
import com.subjex.platform.contract.notification.NotificationDispatcher;
import com.subjex.platform.contract.notification.NotificationPort;
import com.subjex.platform.contract.notification.NotificationReceipt;
import com.subjex.platform.contract.notification.NotificationRecipient;
import com.subjex.platform.contract.notification.NotificationRequested;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.task.TaskLifecycle;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NotificationPortAcceptanceTest — WP5-3 formal package acceptance (ADR 0017).
 * This is the named lock for closing WP5. It does not repeat the WP5-1/WP5-2 focused suites
 * ({@link NotificationPortEnqueueTest}, {@link NotificationDispatchTest}): it walks the three
 * acceptance criteria — a domain-style caller sends one template via {@link NotificationPort};
 * a forced failure is visible as a dead letter and can be replayed; cross-tenant send is denied —
 * and checks that notification kernel sources carry no medical/domain copy, no second outbox
 * product, and no merge with {@code VerificationCodeSender}.
 * <p>
 * 正式关包验收：不重复 WP5-1/WP5-2 聚焦套件。走通三条验收：域风格调用方经端口发出一条模板消息；
 * 失败可查并可重放；禁止跨租户发信。并检查通知内核源码无医疗/域文案、无第二套出箱、未合并验证码发送器。
 * 租户失败关闭、4000 字符上限、OutboxRelay 不夺走通知行、生产未配置失败关闭，由既有聚焦套件锁定。
 */
class NotificationPortAcceptanceTest {

    private static final String TENANT_A = "tenant-wp53-a";
    private static final String TENANT_B = "tenant-wp53-b";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.systemUTC();

    @Test
    void domainModuleSendsOneTemplateThroughPortToMockSink() {
        Fixture fx = Fixture.open();
        NotificationReceipt receipt = fx.port.enqueue(orderApproved(TENANT_A, endUserIn(TENANT_A)));

        assertEquals(NotificationRequested.EVENT_NAME, receipt.eventName());
        assertEquals(OutboxState.PENDING, receipt.eventState());
        assertEquals(1, fx.count("outbox_event"));
        assertEquals(NotificationRequested.EVENT_NAME, fx.eventName(receipt.notificationId()));

        assertEquals(1, fx.dispatcher.dispatchPending(10));
        assertEquals(OutboxState.PUBLISHED.name(), fx.stateOf(receipt.notificationId()));

        NotificationDispatchRecord sent = only(fx.sink.sent());
        assertEquals("order.approved", sent.templateId());
        assertEquals(NotificationChannel.SMS, sent.channel());
        assertEquals("ORD-53", sent.parameters().get("orderId"));
        assertEquals(TENANT_A, sent.tenantId());
        assertEquals("13800138053", sent.recipient().destination());

        JsonNode body = MAPPER.readTree(fx.eventBody(receipt.notificationId()));
        assertEquals("order.approved", body.get("templateId").asText());
        assertEquals("ORD-53", body.get("parameters").get("orderId").asText());
        assertThat(body.has("title")).isFalse();
        assertThat(body.has("body")).isFalse();
        assertThat(body.has("message")).isFalse();
        assertThat(fx.eventBody(receipt.notificationId()))
                .doesNotContain("Your order is approved")
                .doesNotContain("您的订单已批准");

        assertEquals(0, fx.count("platform_task"));
        assertEquals(0, fx.inboxRows());
    }

    @Test
    void forcedFailureIsQueryableAsDeadLetterAndReplaySucceeds() {
        Fixture fx = Fixture.open();
        NotificationReceipt receipt = fx.port.enqueue(orderApproved(TENANT_A, endUserIn(TENANT_A)));
        fx.sink.failNext(TaskLifecycle.DEFAULT_MAX_ATTEMPT);
        for (int i = 0; i < TaskLifecycle.DEFAULT_MAX_ATTEMPT; i++) {
            assertEquals(0, fx.dispatcher.dispatchPending(10));
        }
        assertEquals(OutboxState.DEAD.name(), fx.stateOf(receipt.notificationId()));
        assertEquals("forced mock adapter failure", fx.failureOf(receipt.notificationId()));
        assertEquals(1, fx.deadLetterCount(receipt.notificationId()));
        assertTrue(fx.sink.sent().isEmpty());

        var after = fx.dispatcher.replayDeadLetter(fx.deadLetterId(receipt.notificationId()));
        assertEquals(OutboxState.PUBLISHED, after.eventState());
        assertEquals(0, fx.deadLetterCount(receipt.notificationId()));
        assertEquals("order.approved", only(fx.sink.sent()).templateId());
        assertEquals("ORD-53", only(fx.sink.sent()).parameters().get("orderId"));
    }

    @Test
    void crossTenantSendIsDenied() {
        Fixture fx = Fixture.open();
        assertThrows(
                NotificationCrossTenantDeniedException.class,
                () -> fx.port.enqueue(orderApproved(
                        TENANT_A, NotificationRecipient.ofDestination("+8613800000053", TENANT_B))));
        assertEquals(0, fx.count("outbox_event"));
        assertEquals(0, fx.dispatcher.dispatchPending(10));
        assertTrue(fx.sink.sent().isEmpty());
        assertEquals(0, fx.inboxRows());
    }

    @Test
    void notificationKernelHasNoDomainCopyOrSecondOutboxOrMergedVerificationSender() throws Exception {
        Path contract = repoRoot().resolve("platform-contract/src/main/java/com/subjex/platform/contract/notification");
        Path app = repoRoot().resolve("platform-app/src/main/java/com/subjex/platform/app/notification");
        assertThat(contract).isDirectory();
        assertThat(app).isDirectory();
        String[] forbiddenDomain = {
            "patient",
            "hospital",
            "medical",
            "clinic",
            "clinical",
            "患者",
            "医院",
            "医疗",
            "就诊",
            "病历",
            "notification_outbox",
            "notification_event"
        };
        try (Stream<Path> walk = Stream.concat(Files.walk(contract), Files.walk(app))) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String lower = source.toLowerCase(Locale.ROOT);
                for (String term : forbiddenDomain) {
                    assertThat(lower)
                            .as("%s must not contain %s", file.getFileName(), term)
                            .doesNotContain(term.toLowerCase(Locale.ROOT));
                }
                // Mentions in comments that the auth-code path stays separate are fine;
                // production code must not import or call the type.
                // 注释里说明验证码仍走独立路径可以；生产代码不得 import 或调用该类型。
                assertThat(source)
                        .as("%s must not import VerificationCodeSender", file.getFileName())
                        .doesNotContain("import com.subjex.platform.app.identity.enduser.VerificationCodeSender;");
            }
        }
        Path migrations = repoRoot().resolve("platform-app/src/main/resources/db/migration");
        try (Stream<Path> walk = Files.walk(migrations)) {
            List<Path> notificationOutboxMigrations = walk.filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!name.endsWith(".sql")) {
                            return false;
                        }
                        try {
                            String sql = Files.readString(path, StandardCharsets.UTF_8)
                                    .toLowerCase(Locale.ROOT);
                            // Permission grants (for example V59 notification.read) are not a second outbox.
                            // 权限授予（例如 V59 的 notification.read）不是第二条出箱。
                            return sql.contains("create table")
                                    && (sql.contains("notification_outbox") || sql.contains("notification_event"));
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    })
                    .toList();
            assertThat(notificationOutboxMigrations)
                    .as("WP5 must reuse outbox_event, not add a notification-specific outbox migration")
                    .isEmpty();
        }
    }

    private static NotificationCommand orderApproved(String tenantId, NotificationRecipient recipient) {
        return new NotificationCommand(
                tenantId,
                "order.approved",
                NotificationChannel.SMS,
                recipient,
                Map.of("orderId", "ORD-53"));
    }

    private static NotificationRecipient endUserIn(String tenantId) {
        return NotificationRecipient.ofSubject(InboxRecipientRealm.END_USER, "end-user-ada", tenantId);
    }

    private static NotificationDispatchRecord only(List<NotificationDispatchRecord> records) {
        assertEquals(1, records.size());
        return records.get(0);
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("platform-app"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate repo root from " + System.getProperty("user.dir"));
    }

    private static final class Fixture {
        private final JdbcTemplate jdbc;
        private final NotificationPort port;
        private final NotificationDispatcher dispatcher;
        private final RecordingNotificationSink sink;

        private Fixture(
                JdbcTemplate jdbc,
                NotificationPort port,
                NotificationDispatcher dispatcher,
                RecordingNotificationSink sink) {
            this.jdbc = jdbc;
            this.port = port;
            this.dispatcher = dispatcher;
            this.sink = sink;
        }

        private static Fixture open() {
            DataSource source = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            seedTenant(jdbc, TENANT_A);
            seedTenant(jdbc, TENANT_B);
            seedEndUser(jdbc, "acct-ada", "end-user-ada", "id-ada", TENANT_A);
            seedPhone(jdbc, "acct-ada", "13800138053");
            RecordingNotificationSink sink = new RecordingNotificationSink(CLOCK);
            NotificationDispatcher dispatcher = new JdbcNotificationDispatcher(
                    jdbc,
                    new TransactionTemplate(new DataSourceTransactionManager(source)),
                    new SingleProcessLock(CLOCK),
                    MAPPER,
                    CLOCK,
                    List.of(
                            new MockNotificationAdapter(NotificationChannel.SMS, sink),
                            new MockNotificationAdapter(NotificationChannel.EMAIL, sink),
                            new MockNotificationAdapter(NotificationChannel.WECHAT_TEMPLATE, sink)),
                    new JdbcAuditPort(jdbc));
            return new Fixture(jdbc, new JdbcNotificationPort(jdbc, MAPPER, CLOCK), dispatcher, sink);
        }

        private static void seedTenant(JdbcTemplate jdbc, String tenantId) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    tenantId,
                    tenantId);
        }

        private static void seedEndUser(
                JdbcTemplate jdbc, String accountId, String subjectId, String identityId, String tenantId) {
            jdbc.update(
                    "INSERT INTO account (account_id, login_name, account_state) VALUES (?, ?, 'ACTIVE')",
                    accountId,
                    "login-" + accountId);
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?, ?, 'PERSON')",
                    subjectId,
                    subjectId);
            jdbc.update(
                    """
                    INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """,
                    identityId,
                    accountId,
                    subjectId,
                    tenantId);
        }

        private String eventName(String eventId) {
            return jdbc.queryForObject(
                    "SELECT event_name FROM outbox_event WHERE event_id = ?", String.class, eventId);
        }

        private String eventBody(String eventId) {
            return jdbc.queryForObject(
                    "SELECT event_body FROM outbox_event WHERE event_id = ?", String.class, eventId);
        }


        private static void seedPhone(JdbcTemplate jdbc, String accountId, String phone) {
            jdbc.update(
                    """
                    INSERT INTO end_user_phone_binding (binding_id, account_id, phone, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    "bind-" + accountId,
                    accountId,
                    phone,
                    Timestamp.from(Instant.parse("2026-11-28T08:00:00Z")));
        }

        private String stateOf(String eventId) {
            return jdbc.queryForObject(
                    "SELECT event_state FROM outbox_event WHERE event_id = ?", String.class, eventId);
        }

        private String failureOf(String eventId) {
            return jdbc.queryForObject(
                    "SELECT failure_reason FROM outbox_event WHERE event_id = ?", String.class, eventId);
        }

        private int deadLetterCount(String eventId) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM dead_letter WHERE origin_id = ? AND discarded_at IS NULL",
                    Integer.class,
                    eventId);
            return n == null ? 0 : n;
        }

        private String deadLetterId(String eventId) {
            return jdbc.queryForObject(
                    "SELECT dead_letter_id FROM dead_letter WHERE origin_id = ? AND discarded_at IS NULL",
                    String.class,
                    eventId);
        }

        private int count(String table) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            return n == null ? 0 : n;
        }

        private int inboxRows() {
            return count("inbox_message") + count("inbox_entry") + count("inbox_fanout_job");
        }
    }
}
