package com.subjex.platform.app.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.payment.CreatePaymentCommand;
import com.subjex.platform.contract.payment.PaymentAmount;
import com.subjex.platform.contract.payment.PaymentCrossTenantDeniedException;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentPort;
import com.subjex.platform.contract.payment.PaymentProviderIds;
import com.subjex.platform.contract.payment.PaymentResultCallback;
import com.subjex.platform.contract.payment.PaymentStatus;
import com.subjex.platform.contract.payment.PaymentStatusChanged;
import com.subjex.platform.contract.payment.RefundCommand;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
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
 * PaymentPortAcceptanceTest — WP6-3 formal package acceptance (ADR 0018).
 * This is the named lock for closing WP6. It does not repeat the WP6-1/WP6-2 focused suites
 * ({@link PaymentPortCreateQueryTest}, {@link PaymentCallbackRefundTest}): it walks the acceptance
 * criteria — a domain-style caller creates an order, receives a successful mock payment, makes a
 * partial refund, and replays a duplicate callback (no double credit); a domain module continues
 * from {@link PaymentPort#queryOrder} and sees one {@code PaymentStatusChanged} row per change on
 * the existing {@code outbox_event}; cross-tenant access is denied — and checks that payment kernel
 * sources carry no commission, split-accounting, tax, or hospital/medical wording, no second outbox
 * table or migration, and no vendor SDK import.
 * <p>
 * 正式关包验收：不重复 WP6-1/WP6-2 聚焦套件。走通验收：域风格调用方经模拟适配器创单、支付成功、部分退款，
 * 并回放重复回调（不二次入账）；域模块经 {@link PaymentPort#queryOrder} 按单据状态继续，且已有
 * {@code outbox_event} 上每次状态变化恰好一行 {@code PaymentStatusChanged}；禁止跨租户访问。
 * 并检查支付内核源码无分润/分账/税务或医院/医疗文案、无第二套出箱表或迁移、未引入厂商 SDK。
 */
class PaymentPortAcceptanceTest {

    private static final String TENANT_A = "tenant-wp63-a";
    private static final String TENANT_B = "tenant-wp63-b";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.systemUTC();

    @Test
    void domainModuleCreatesPaysPartiallyRefundsAndReplayDoesNotDoubleCredit() {
        Fixture fx = Fixture.open();
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 100L, "CNY", "ORD-63"));

        // 域模块按查单状态继续自身流程 / A domain module continues its own flow from query status
        PaymentDocument pending = fx.port.queryOrder(TENANT_A, created.paymentId());
        assertEquals(PaymentStatus.PENDING, pending.status());
        assertEquals(100L, pending.amount().amountMinor());
        assertEquals("CNY", pending.amount().currency());
        assertEquals(0, fx.statusChangedCount());

        PaymentDocument paid = fx.port.applyPaymentResult(success(TENANT_A, created.paymentId(), "notify-63"));
        assertEquals(PaymentStatus.SUCCEEDED, paid.status());
        assertEquals(PaymentStatus.SUCCEEDED, fx.port.queryOrder(TENANT_A, created.paymentId()).status());
        assertEquals(1, fx.statusChangedCount());
        assertStatusChanged(fx, created.paymentId(), PaymentStatus.SUCCEEDED, 100L, 0L);

        PaymentDocument partial = fx.port.refund(refund(TENANT_A, created.paymentId(), 30L, "CNY", "refund-63"));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, partial.status());
        assertEquals(30L, partial.refundedAmount().amountMinor());
        PaymentDocument afterRefund = fx.port.queryOrder(TENANT_A, created.paymentId());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, afterRefund.status());
        assertEquals(30L, afterRefund.refundedAmount().amountMinor());
        assertEquals(1, afterRefund.refunds().size());
        assertEquals(2, fx.statusChangedCount());
        assertStatusChanged(fx, created.paymentId(), PaymentStatus.PARTIALLY_REFUNDED, 100L, 30L);

        // 重复回调不得二次入账或再写出箱 / A duplicate callback must not double-credit or write another outbox row
        PaymentDocument replayed = fx.port.applyPaymentResult(success(TENANT_A, created.paymentId(), "notify-63"));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, replayed.status());
        assertEquals(30L, replayed.refundedAmount().amountMinor());
        assertEquals(1, replayed.refunds().size());
        assertEquals(2, fx.statusChangedCount());
        assertEquals(1, fx.voucherFacts(created.paymentId(), JdbcPaymentPort.FACT_PAYMENT_SUCCEEDED));
        assertEquals(0, fx.count("platform_task"));
        assertEquals(0, fx.count("idempotency_claim"));
        assertEquals(fx.statusChangedCount(), fx.count("outbox_event"));
    }

    @Test
    void crossTenantAccessIsDenied() {
        Fixture fx = Fixture.open();
        PaymentDocument created = fx.port.createOrder(order(TENANT_A, 40L, "CNY", "ORD-63-x"));

        assertThrows(
                PaymentCrossTenantDeniedException.class,
                () -> fx.port.queryOrder(TENANT_B, created.paymentId()));
        assertThrows(
                PaymentCrossTenantDeniedException.class,
                () -> fx.port.applyPaymentResult(success(TENANT_B, created.paymentId(), "notify-x")));
        assertThrows(
                PaymentCrossTenantDeniedException.class,
                () -> fx.port.refund(refund(TENANT_B, created.paymentId(), 10L, "CNY", "refund-x")));

        PaymentDocument still = fx.port.queryOrder(TENANT_A, created.paymentId());
        assertEquals(PaymentStatus.PENDING, still.status());
        assertEquals(0L, still.refundedAmount().amountMinor());
        assertEquals(0, fx.statusChangedCount());
        assertEquals(0, fx.count("payment_refund"));
    }

    @Test
    void paymentKernelHasNoDomainFinanceWordingOrSecondOutboxOrVendorSdk() throws Exception {
        Path contract = repoRoot().resolve("platform-contract/src/main/java/com/subjex/platform/contract/payment");
        Path app = repoRoot().resolve("platform-app/src/main/java/com/subjex/platform/app/payment");
        assertThat(contract).isDirectory();
        assertThat(app).isDirectory();
        String[] forbiddenDomain = {
            "commission",
            "split-accounting",
            "split_accounting",
            "revenue share",
            "hospital",
            "medical",
            "clinic",
            "clinical",
            "patient",
            "fapiao",
            "分润",
            "分账",
            "税务",
            "发票",
            "医院",
            "医疗",
            "就诊",
            "病历",
            "患者"
        };
        String[] vendorImports = {
            "import com.alipay.",
            "import com.alipay.api.",
            "import com.wechat.pay.",
            "import com.github.binarywang.",
            "import com.github.wxpay.",
            "import com.tencent.wechatpay.",
            "import com.ijpay.",
            "import com.github.wechatpay."
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
                // Reserved identifier names (WECHAT_PAY / ALIPAY) are allowed; vendor SDK imports are not.
                // 预留标识名称（WECHAT_PAY / ALIPAY）可以出现；厂商 SDK 的 import 不行。
                for (String line : vendorImports) {
                    assertThat(source)
                            .as("%s must not import a vendor SDK (%s)", file.getFileName(), line)
                            .doesNotContain(line);
                }
            }
        }
        Path migrations = repoRoot().resolve("platform-app/src/main/resources/db/migration");
        try (Stream<Path> walk = Files.walk(migrations)) {
            List<Path> secondOutbox = walk.filter(path -> path.toString().endsWith(".sql"))
                    .filter(path -> createsSecondOutbox(path))
                    .toList();
            assertThat(secondOutbox)
                    .as("WP6 must reuse outbox_event, not add a second outbox table")
                    .isEmpty();
        }
    }

    private static boolean createsSecondOutbox(Path path) {
        try {
            String sql = Files.readString(path, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            for (String line : sql.split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.startsWith("--") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (trimmed.contains("create table")
                        && trimmed.contains("outbox")
                        && !trimmed.contains("outbox_event")) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            throw new IllegalStateException("could not read " + path, ex);
        }
    }

    private static void assertStatusChanged(
            Fixture fx, String paymentId, PaymentStatus status, long amountMinor, long refundedMinor) {
        List<Map<String, Object>> rows = fx.jdbc.queryForList(
                "SELECT event_name, event_body FROM outbox_event WHERE event_name = ? ORDER BY occurred_at, event_id",
                PaymentStatusChanged.EVENT_NAME);
        assertThat(rows).isNotEmpty();
        JsonNode last = MAPPER.readTree((String) rows.get(rows.size() - 1).get("event_body"));
        assertEquals(PaymentStatusChanged.EVENT_NAME, rows.get(rows.size() - 1).get("event_name"));
        assertEquals(paymentId, last.get("paymentId").asText());
        assertEquals(TENANT_A, last.get("tenantId").asText());
        assertEquals(status.name(), last.get("status").asText());
        assertEquals(amountMinor, last.get("amountMinor").asLong());
        assertEquals(refundedMinor, last.get("refundedAmountMinor").asLong());
        assertEquals("CNY", last.get("currency").asText());
    }

    private static CreatePaymentCommand order(String tenantId, long amountMinor, String currency, String reference) {
        return new CreatePaymentCommand(
                tenantId,
                new PaymentAmount(amountMinor, currency),
                reference,
                "payer-ada",
                "END_USER",
                null,
                PaymentProviderIds.MOCK);
    }

    private static PaymentResultCallback success(String tenantId, String paymentId, String notificationId) {
        return new PaymentResultCallback(tenantId, paymentId, notificationId, PaymentStatus.SUCCEEDED, "prov-ref-63");
    }

    private static RefundCommand refund(
            String tenantId, String paymentId, long amountMinor, String currency, String token) {
        return new RefundCommand(tenantId, paymentId, new PaymentAmount(amountMinor, currency), token);
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
        private final PaymentPort port;

        private Fixture(JdbcTemplate jdbc, PaymentPort port) {
            this.jdbc = jdbc;
            this.port = port;
        }

        private static Fixture open() {
            DataSource source = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            seedTenant(jdbc, TENANT_A);
            seedTenant(jdbc, TENANT_B);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            PaymentPort port = new JdbcPaymentPort(
                    jdbc,
                    tx,
                    CLOCK,
                    MAPPER,
                    List.of(new MockPaymentAdapter(new RecordingPaymentSink())),
                    new JdbcAuditPort(jdbc));
            return new Fixture(jdbc, port);
        }

        private static void seedTenant(JdbcTemplate jdbc, String tenantId) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    tenantId,
                    tenantId);
        }

        private int count(String table) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            return n == null ? 0 : n;
        }

        private int statusChangedCount() {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE event_name = ?",
                    Integer.class,
                    PaymentStatusChanged.EVENT_NAME);
            return n == null ? 0 : n;
        }

        private int voucherFacts(String paymentId, String factKind) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payment_voucher WHERE payment_id = ? AND fact_kind = ?",
                    Integer.class,
                    paymentId,
                    factKind);
            return n == null ? 0 : n;
        }
    }
}
