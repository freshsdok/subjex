package com.subjex.platform.app.payment;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.jdbc.PlatformTables;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.payment.CreatePaymentCommand;
import com.subjex.platform.contract.payment.PaymentAdapterUnconfiguredException;
import com.subjex.platform.contract.payment.PaymentAmount;
import com.subjex.platform.contract.payment.PaymentCrossTenantDeniedException;
import com.subjex.platform.contract.payment.PaymentDocument;
import com.subjex.platform.contract.payment.PaymentIdempotencyConflictException;
import com.subjex.platform.contract.payment.PaymentNotFoundException;
import com.subjex.platform.contract.payment.PaymentPort;
import com.subjex.platform.contract.payment.PaymentProviderAdapter;
import com.subjex.platform.contract.payment.PaymentProviderIds;
import com.subjex.platform.contract.payment.PaymentRefundLine;
import com.subjex.platform.contract.payment.PaymentResultCallback;
import com.subjex.platform.contract.payment.PaymentStatus;
import com.subjex.platform.contract.payment.PaymentStatusChanged;
import com.subjex.platform.contract.payment.RefundCommand;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.tenant.TenantMissingException;
import com.subjex.platform.contract.tenant.TenantState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcPaymentPort — JDBC 支付端口：创单、查单、幂等回调与退款，并在已有出箱上写 {@link PaymentStatusChanged}。
 * <p>
 * This path does <em>not</em> call {@code TaskMessagePort.submit}, does not insert {@code platform_task},
 * and does not overload {@code idempotency_claim}. Callback and refund require a configured
 * {@link PaymentProviderAdapter}; production stays fail-closed when none is registered.
 * 本路径不调用 {@code TaskMessagePort.submit}，不写 {@code platform_task}，也不占用 {@code idempotency_claim}。
 * 回调与退款需要已配置的 {@link PaymentProviderAdapter}；生产未注册则失败关闭。
 */
public final class JdbcPaymentPort implements PaymentPort {

    /**
     * Reserved operator-home tenant; never a default business payment tenant.
     * 保留的操作员归属租户；绝不可作为默认业务支付租户。
     */
    public static final String RESERVED_TENANT = "platform";

    /** Create-order voucher fact kind / 创单凭证事实类型 */
    public static final String FACT_ORDER_CREATED = "ORDER_CREATED";

    /** Payment-succeeded voucher fact kind / 支付成功凭证事实类型 */
    public static final String FACT_PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";

    /** Payment-failed voucher fact kind / 支付失败凭证事实类型 */
    public static final String FACT_PAYMENT_FAILED = "PAYMENT_FAILED";

    /** Refund-applied voucher fact kind / 退款入账凭证事实类型 */
    public static final String FACT_REFUND_APPLIED = "REFUND_APPLIED";

    /**
     * {@code outbox_event.event_body} VARCHAR(4000). Fail closed rather than invent a payload store.
     * {@code outbox_event.event_body} 为 VARCHAR(4000)。超限失败关闭，不另起载荷库。
     */
    public static final int MAX_EVENT_BODY_CHARS = 4000;

    /**
     * Audit action for a charge (payment-result) attempt — 扣费（支付结果）尝试的审计动作。
     */
    public static final String CHARGE_ACTION = "payment.charge";

    /**
     * Audit action for a refund attempt — 退款尝试的审计动作。
     */
    public static final String REFUND_ACTION = "payment.refund";

    /**
     * System actor for in-process payment port calls — 进程内支付端口调用的系统操作者。
     */
    public static final String AUDIT_ACTOR = "payment-port";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Map<String, PaymentProviderAdapter> adapters;
    private final AuditPort audit;

    /**
     * Test helper: default mock adapter so create/query tests can stay adapter-agnostic.
     * 测试辅助：默认挂上模拟适配器，便于创单/查单测试不关心适配器装配。
     */
    public JdbcPaymentPort(JdbcTemplate jdbc, TransactionTemplate transactionTemplate, Clock clock) {
        this(
                jdbc,
                transactionTemplate,
                clock,
                new ObjectMapper(),
                List.of(new MockPaymentAdapter(new RecordingPaymentSink())),
                new JdbcAuditPort(jdbc));
    }

    public JdbcPaymentPort(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            Clock clock,
            ObjectMapper objectMapper,
            Collection<PaymentProviderAdapter> adapters,
            AuditPort audit) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(adapters, "adapters");
        LinkedHashMap<String, PaymentProviderAdapter> map = new LinkedHashMap<>();
        for (PaymentProviderAdapter adapter : adapters) {
            map.putIfAbsent(adapter.providerId(), adapter);
        }
        this.adapters = Map.copyOf(map);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public PaymentDocument createOrder(CreatePaymentCommand command) {
        Objects.requireNonNull(command, "command");
        String tenantId = requireActiveBusinessTenant(command.tenantId());
        String fingerprint = fingerprint(command);
        // 相同创单幂等令牌且指纹一致则回放原单 / Replay the original order when the create token matches
        if (command.idempotencyToken() != null) {
            PaymentDocument existing = findByCreateToken(tenantId, command.idempotencyToken());
            if (existing != null) {
                String stored = storedCreateFingerprint(tenantId, existing.paymentId());
                if (!fingerprint.equals(stored)) {
                    throw new PaymentIdempotencyConflictException(tenantId, command.idempotencyToken());
                }
                return existing;
            }
        }
        String paymentId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        Timestamp ts = PlatformTables.timestamp(now);
        transactionTemplate.executeWithoutResult(status -> {
            // 创建支付单 / Create payment order
            jdbc.update(
                    "INSERT INTO payment ("
                            + "payment_id, tenant_id, amount_minor, currency, refunded_amount_minor, "
                            + "payment_status, domain_reference, payer_subject_id, payer_realm, "
                            + "provider_id, provider_reference, create_idempotency_token, "
                            + "create_idempotency_fingerprint, callback_notification_id, "
                            + "callback_notification_fingerprint, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    paymentId,
                    tenantId,
                    command.amount().amountMinor(),
                    command.amount().currency(),
                    0L,
                    PaymentStatus.PENDING.name(),
                    command.reference(),
                    command.payerSubjectId(),
                    command.payerRealm(),
                    command.providerId(),
                    null,
                    command.idempotencyToken(),
                    command.idempotencyToken() == null ? null : fingerprint,
                    null,
                    null,
                    ts,
                    ts);
            // 写下创单凭证 / Write the create-order voucher fact
            insertVoucher(
                    tenantId,
                    paymentId,
                    null,
                    FACT_ORDER_CREATED,
                    command.amount().amountMinor(),
                    command.amount().currency(),
                    PaymentStatus.PENDING,
                    command.providerId(),
                    null,
                    ts);
        });
        return loadDocument(tenantId, paymentId);
    }

    @Override
    public PaymentDocument queryOrder(String tenantId, String paymentId) {
        String tenant = requireActiveBusinessTenant(tenantId);
        if (paymentId == null || paymentId.isBlank()) {
            throw new IllegalArgumentException("paymentId required");
        }
        String id = paymentId.trim();
        List<PaymentRow> rows = jdbc.query(
                "SELECT * FROM payment WHERE payment_id = ?", (row, n) -> mapRow(row), id);
        if (rows.isEmpty()) {
            throw new PaymentNotFoundException(tenant, id);
        }
        PaymentRow found = rows.get(0);
        // 禁止跨租户查单 / Deny a cross-tenant query
        if (!tenant.equals(found.tenantId)) {
            throw new PaymentCrossTenantDeniedException(tenant, found.tenantId);
        }
        return toDocument(found, loadRefunds(tenant, id));
    }

    /** Default page size for operator lists — 操作员列表默认条数. */
    public static final int DEFAULT_LIST_LIMIT = 100;

    /** Hard cap on operator list size — 操作员列表条数上限. */
    public static final int MAX_LIST_LIMIT = 200;

    /**
     * List payment documents in an active business tenant, newest first.
     * Optional status and payer filters. Not on {@link PaymentPort}: operator visibility only.
     * 在已激活的业务租户内列出支付单，按时间从新到旧；可按状态或付款人筛选。
     * 不属于 {@link PaymentPort}，仅供操作员查看，不办理结账或退款。
     */
    public List<PaymentDocument> listOrders(
            String tenantId, PaymentStatus status, String payerSubjectId, Integer limit) {
        String tenant = requireActiveBusinessTenant(tenantId);
        int cap = normalizeListLimit(limit);
        String payer = payerSubjectId == null || payerSubjectId.isBlank() ? null : payerSubjectId.trim();
        StringBuilder sql = new StringBuilder("SELECT * FROM payment WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (status != null) {
            sql.append(" AND payment_status = ?");
            args.add(status.name());
        }
        if (payer != null) {
            sql.append(" AND payer_subject_id = ?");
            args.add(payer);
        }
        sql.append(" ORDER BY created_at DESC, payment_id DESC LIMIT ?");
        args.add(cap);
        List<PaymentRow> rows = jdbc.query(sql.toString(), (row, n) -> mapRow(row), args.toArray());
        return rows.stream().map(row -> toDocument(row, loadRefunds(tenant, row.paymentId))).toList();
    }

    private static int normalizeListLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    @Override
    public PaymentDocument applyPaymentResult(PaymentResultCallback callback) {
        Objects.requireNonNull(callback, "callback");
        String tenantId = requireActiveBusinessTenant(callback.tenantId());
        if (callback.paymentId() == null || callback.paymentId().isBlank()) {
            throw new IllegalArgumentException("paymentId required");
        }
        if (callback.providerNotificationId() == null || callback.providerNotificationId().isBlank()) {
            throw new IllegalArgumentException("providerNotificationId required");
        }
        if (callback.resultingStatus() != PaymentStatus.SUCCEEDED
                && callback.resultingStatus() != PaymentStatus.FAILED) {
            throw new IllegalArgumentException("resultingStatus must be SUCCEEDED or FAILED");
        }
        String paymentId = callback.paymentId();
        String notificationId = callback.providerNotificationId();
        String fingerprint = callbackFingerprint(callback);
        try {
            return transactionTemplate.execute(status -> {
            PaymentRow found = lockPayment(paymentId);
            if (found == null) {
                throw new PaymentNotFoundException(tenantId, paymentId);
            }
            // 禁止跨租户回调 / Deny a cross-tenant callback
            if (!tenantId.equals(found.tenantId)) {
                throw new PaymentCrossTenantDeniedException(tenantId, found.tenantId);
            }
            PaymentDocument current = toDocument(found, loadRefunds(tenantId, paymentId));
            // 同一回调通知且指纹一致则回放，不二次入账 / Replay the same callback; do not credit twice
            if (notificationId.equals(found.callbackNotificationId)) {
                if (!fingerprint.equals(found.callbackFingerprint)) {
                    throw new PaymentIdempotencyConflictException(tenantId, notificationId);
                }
                return current;
            }
            PaymentProviderAdapter adapter = requireConfiguredAdapter(found.providerId);
            adapter.acceptPaymentResult(current, callback);
            if (found.status != PaymentStatus.PENDING) {
                throw new IllegalStateException(
                        "payment " + paymentId + " is already " + found.status + " and cannot be credited again");
            }
            // 应用支付结果并写凭证与出箱 / Apply the payment result and write voucher plus outbox
            Instant now = clock.instant();
            Timestamp ts = PlatformTables.timestamp(now);
            jdbc.update(
                    "UPDATE payment SET payment_status = ?, provider_reference = ?, "
                            + "callback_notification_id = ?, callback_notification_fingerprint = ?, updated_at = ? "
                            + "WHERE payment_id = ? AND tenant_id = ?",
                    callback.resultingStatus().name(),
                    callback.providerReference(),
                    notificationId,
                    fingerprint,
                    ts,
                    paymentId,
                    tenantId);
            String fact = callback.resultingStatus() == PaymentStatus.SUCCEEDED
                    ? FACT_PAYMENT_SUCCEEDED
                    : FACT_PAYMENT_FAILED;
            insertVoucher(
                    tenantId,
                    paymentId,
                    null,
                    fact,
                    found.amountMinor,
                    found.currency,
                    callback.resultingStatus(),
                    found.providerId,
                    callback.providerReference(),
                    ts);
            insertStatusChanged(
                    tenantId,
                    paymentId,
                    callback.resultingStatus(),
                    found.amountMinor,
                    found.refundedAmountMinor,
                    found.currency,
                    found.providerId,
                    callback.providerReference(),
                    ts);
            PaymentDocument applied = loadDocument(tenantId, paymentId);
            recordAttempt(tenantId, found.providerId, paymentId, CHARGE_ACTION, AuditOutcome.ALLOWED);
            return applied;
        });
        } catch (PaymentAdapterUnconfiguredException ex) {
            recordAttempt(tenantId, providerIdOf(paymentId), paymentId, CHARGE_ACTION, AuditOutcome.REFUSED);
            throw ex;
        } catch (PaymentAdapterSendFailedException ex) {
            recordAttempt(tenantId, providerIdOf(paymentId), paymentId, CHARGE_ACTION, AuditOutcome.FAILED);
            throw ex;
        } catch (PaymentCrossTenantDeniedException ex) {
            recordAttempt(tenantId, providerIdOf(paymentId), paymentId, CHARGE_ACTION, AuditOutcome.REFUSED);
            throw ex;
        }
    }

    @Override
    public PaymentDocument refund(RefundCommand command) {
        Objects.requireNonNull(command, "command");
        String tenantId = requireActiveBusinessTenant(command.tenantId());
        if (command.paymentId() == null || command.paymentId().isBlank()) {
            throw new IllegalArgumentException("paymentId required");
        }
        // 退款金额必须严格为正 / Refund amount must be strictly positive
        if (command.amount().amountMinor() <= 0) {
            throw new IllegalArgumentException("refund amount must be a positive minor-unit integer");
        }
        String paymentId = command.paymentId();
        String refundFingerprint = refundFingerprint(command);
        try {
            return transactionTemplate.execute(status -> {
            PaymentRow found = lockPayment(paymentId);
            if (found == null) {
                throw new PaymentNotFoundException(tenantId, paymentId);
            }
            // 禁止跨租户退款 / Deny a cross-tenant refund
            if (!tenantId.equals(found.tenantId)) {
                throw new PaymentCrossTenantDeniedException(tenantId, found.tenantId);
            }
            if (command.idempotencyToken() != null) {
                PaymentDocument replayed = findRefundReplay(tenantId, paymentId, command.idempotencyToken(), refundFingerprint);
                if (replayed != null) {
                    return replayed;
                }
            }
            PaymentDocument current = toDocument(found, loadRefunds(tenantId, paymentId));
            PaymentProviderAdapter adapter = requireConfiguredAdapter(found.providerId);
            adapter.acceptRefund(current, command);
            if (found.status != PaymentStatus.SUCCEEDED && found.status != PaymentStatus.PARTIALLY_REFUNDED) {
                throw new IllegalStateException(
                        "payment " + paymentId + " in status " + found.status + " cannot be refunded");
            }
            if (!found.currency.equals(command.amount().currency())) {
                throw new IllegalArgumentException(
                        "refund currency " + command.amount().currency() + " must match payment currency " + found.currency);
            }
            long remaining = found.amountMinor - found.refundedAmountMinor;
            if (command.amount().amountMinor() > remaining) {
                throw new IllegalArgumentException(
                        "refund amount exceeds remaining refundable amount " + remaining);
            }
            // 申请退款（含部分退款） / Apply a refund, including partial
            long newRefunded = found.refundedAmountMinor + command.amount().amountMinor();
            PaymentStatus newStatus =
                    newRefunded == found.amountMinor ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
            Instant now = clock.instant();
            Timestamp ts = PlatformTables.timestamp(now);
            String refundId = UUID.randomUUID().toString();
            jdbc.update(
                    "INSERT INTO payment_refund ("
                            + "refund_id, tenant_id, payment_id, amount_minor, currency, refund_status, "
                            + "refund_idempotency_token, refund_idempotency_fingerprint, provider_refund_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    refundId,
                    tenantId,
                    paymentId,
                    command.amount().amountMinor(),
                    command.amount().currency(),
                    newStatus.name(),
                    command.idempotencyToken(),
                    command.idempotencyToken() == null ? null : refundFingerprint,
                    null,
                    ts);
            jdbc.update(
                    "UPDATE payment SET refunded_amount_minor = ?, payment_status = ?, updated_at = ? "
                            + "WHERE payment_id = ? AND tenant_id = ?",
                    newRefunded,
                    newStatus.name(),
                    ts,
                    paymentId,
                    tenantId);
            insertVoucher(
                    tenantId,
                    paymentId,
                    refundId,
                    FACT_REFUND_APPLIED,
                    command.amount().amountMinor(),
                    command.amount().currency(),
                    newStatus,
                    found.providerId,
                    found.providerReference,
                    ts);
            insertStatusChanged(
                    tenantId,
                    paymentId,
                    newStatus,
                    found.amountMinor,
                    newRefunded,
                    found.currency,
                    found.providerId,
                    found.providerReference,
                    ts);
            PaymentDocument applied = loadDocument(tenantId, paymentId);
            recordAttempt(tenantId, found.providerId, paymentId, REFUND_ACTION, AuditOutcome.ALLOWED);
            return applied;
        });
        } catch (PaymentAdapterUnconfiguredException ex) {
            recordAttempt(tenantId, providerIdOf(paymentId), paymentId, REFUND_ACTION, AuditOutcome.REFUSED);
            throw ex;
        } catch (PaymentAdapterSendFailedException ex) {
            recordAttempt(tenantId, providerIdOf(paymentId), paymentId, REFUND_ACTION, AuditOutcome.FAILED);
            throw ex;
        } catch (PaymentCrossTenantDeniedException ex) {
            recordAttempt(tenantId, providerIdOf(paymentId), paymentId, REFUND_ACTION, AuditOutcome.REFUSED);
            throw ex;
        }
    }

    /**
     * Explicit business tenant, ACTIVE, never reserved {@code platform}, never auto-created.
     * 必须是明确的业务租户且为 ACTIVE，绝不用保留租户 {@code platform}，也绝不自动建户。
     * Reuses the same fail-closed checks as {@code JdbcNotificationPort.requireActiveBusinessTenant}.
     * 复用与 {@code JdbcNotificationPort.requireActiveBusinessTenant} 相同的失败关闭校验。
     */
    private String requireActiveBusinessTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String id = tenantId.trim();
        if (RESERVED_TENANT.equals(id)) {
            throw new IllegalArgumentException(
                    "reserved tenant " + RESERVED_TENANT + " is not a payment home");
        }
        List<String> states = jdbc.query(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> row.getString("tenant_state"),
                id);
        if (states.isEmpty()) {
            throw new TenantNotFoundException(id);
        }
        if (!TenantState.ACTIVE.name().equals(states.get(0))) {
            throw new TenantDisabledException(id);
        }
        return id;
    }

    /**
     * Charge or refund attempt target: provider, action, and a masked payment id. No keys or full identifiers.
     * 扣费/退款尝试对象：供应商、动作与遮蔽后的支付单标识。不含密钥或完整标识。
     */
    static String actionTarget(String providerId, String action, String paymentId) {
        String provider = providerId == null || providerId.isBlank() ? "unknown" : providerId.trim();
        String kind = CHARGE_ACTION.equals(action) ? "charge" : "refund";
        return provider + ":" + kind + ":" + maskIdentifier(paymentId);
    }

    /**
     * Mask an identifier like the OTP slice (keep the last 4 characters).
     * 与验证码切片一样遮蔽标识（只留末 4 位）。
     */
    static String maskIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "****";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "*".repeat(trimmed.length() - 4) + trimmed.substring(trimmed.length() - 4);
    }

    private void recordAttempt(
            String tenantId, String providerId, String paymentId, String action, AuditOutcome outcome) {
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                tenantId,
                AUDIT_ACTOR,
                action,
                actionTarget(providerId, action, paymentId),
                outcome,
                clock.instant()));
    }

    private String providerIdOf(String paymentId) {
        List<PaymentRow> rows = jdbc.query(
                "SELECT * FROM payment WHERE payment_id = ?", (row, n) -> mapRow(row), paymentId);
        if (rows.isEmpty()) {
            return "unknown";
        }
        String id = rows.get(0).providerId;
        return id == null || id.isBlank() ? "unknown" : id;
    }

    private PaymentProviderAdapter requireConfiguredAdapter(String providerId) {
        PaymentProviderAdapter adapter = adapters.get(providerId);
        String id = adapter == null ? providerId : adapter.providerId();
        if (adapter == null || !adapter.configured()) {
            throw new PaymentAdapterUnconfiguredException(id == null ? PaymentProviderIds.MOCK : id);
        }
        return adapter;
    }

    private PaymentRow lockPayment(String paymentId) {
        List<PaymentRow> rows = jdbc.query(
                "SELECT * FROM payment WHERE payment_id = ? FOR UPDATE", (row, n) -> mapRow(row), paymentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private PaymentDocument findByCreateToken(String tenantId, String token) {
        List<PaymentRow> rows = jdbc.query(
                "SELECT * FROM payment WHERE tenant_id = ? AND create_idempotency_token = ?",
                (row, n) -> mapRow(row),
                tenantId,
                token);
        if (rows.isEmpty()) {
            return null;
        }
        PaymentRow found = rows.get(0);
        return toDocument(found, loadRefunds(tenantId, found.paymentId));
    }

    private String storedCreateFingerprint(String tenantId, String paymentId) {
        List<String> values = jdbc.query(
                "SELECT create_idempotency_fingerprint FROM payment WHERE tenant_id = ? AND payment_id = ?",
                (row, n) -> row.getString("create_idempotency_fingerprint"),
                tenantId,
                paymentId);
        return values.isEmpty() ? null : values.get(0);
    }

    private PaymentDocument findRefundReplay(
            String tenantId, String paymentId, String token, String fingerprint) {
        List<String> stored = jdbc.query(
                "SELECT refund_idempotency_fingerprint FROM payment_refund "
                        + "WHERE tenant_id = ? AND payment_id = ? AND refund_idempotency_token = ?",
                (row, n) -> row.getString("refund_idempotency_fingerprint"),
                tenantId,
                paymentId,
                token);
        if (stored.isEmpty()) {
            return null;
        }
        if (!fingerprint.equals(stored.get(0))) {
            throw new PaymentIdempotencyConflictException(tenantId, token);
        }
        // 相同退款令牌且指纹一致则回放，不二次退款 / Replay the same refund token; do not refund twice
        return loadDocument(tenantId, paymentId);
    }

    private PaymentDocument loadDocument(String tenantId, String paymentId) {
        List<PaymentRow> rows = jdbc.query(
                "SELECT * FROM payment WHERE tenant_id = ? AND payment_id = ?",
                (row, n) -> mapRow(row),
                tenantId,
                paymentId);
        if (rows.isEmpty()) {
            throw new PaymentNotFoundException(tenantId, paymentId);
        }
        return toDocument(rows.get(0), loadRefunds(tenantId, paymentId));
    }

    private List<PaymentRefundLine> loadRefunds(String tenantId, String paymentId) {
        return jdbc.query(
                "SELECT refund_id, amount_minor, currency, refund_status, provider_refund_id, created_at "
                        + "FROM payment_refund WHERE tenant_id = ? AND payment_id = ? "
                        + "ORDER BY created_at, refund_id",
                (row, n) -> new PaymentRefundLine(
                        row.getString("refund_id"),
                        new PaymentAmount(row.getLong("amount_minor"), trimCurrency(row.getString("currency"))),
                        row.getString("refund_status"),
                        row.getString("provider_refund_id"),
                        instant(row.getTimestamp("created_at"))),
                tenantId,
                paymentId);
    }

    private void insertVoucher(
            String tenantId,
            String paymentId,
            String refundId,
            String factKind,
            long amountMinor,
            String currency,
            PaymentStatus resultingStatus,
            String providerId,
            String providerReference,
            Timestamp ts) {
        jdbc.update(
                "INSERT INTO payment_voucher ("
                        + "voucher_id, tenant_id, payment_id, refund_id, fact_kind, "
                        + "amount_minor, currency, resulting_status, provider_id, "
                        + "provider_reference, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                tenantId,
                paymentId,
                refundId,
                factKind,
                amountMinor,
                currency,
                resultingStatus.name(),
                providerId,
                providerReference,
                ts);
    }

    private void insertStatusChanged(
            String tenantId,
            String paymentId,
            PaymentStatus status,
            long amountMinor,
            long refundedAmountMinor,
            String currency,
            String providerId,
            String providerReference,
            Timestamp ts) {
        String eventId = UUID.randomUUID().toString();
        PaymentStatusChanged body = new PaymentStatusChanged(
                eventId,
                tenantId,
                paymentId,
                status,
                amountMinor,
                refundedAmountMinor,
                currency,
                providerId,
                providerReference);
        String json = writeBody(body);
        if (json.length() > MAX_EVENT_BODY_CHARS) {
            throw new IllegalArgumentException(
                    "payment event_body exceeds " + MAX_EVENT_BODY_CHARS + " characters");
        }
        // 在同一事务里把 PaymentStatusChanged 写入已有出箱 / Write PaymentStatusChanged on the existing outbox in the same transaction
        jdbc.update(
                "INSERT INTO outbox_event ("
                        + "event_id, tenant_id, event_name, event_body, event_state, trace_id, "
                        + "attempt_count, failure_reason, occurred_at, published_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventId,
                tenantId,
                PaymentStatusChanged.EVENT_NAME,
                json,
                OutboxState.PENDING.name(),
                eventId,
                0,
                null,
                ts,
                null);
    }

    private String writeBody(PaymentStatusChanged body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JacksonException ex) {
            throw new IllegalStateException("could not write payment status changed body", ex);
        }
    }

    private static PaymentDocument toDocument(PaymentRow row, List<PaymentRefundLine> refunds) {
        return new PaymentDocument(
                row.paymentId,
                row.tenantId,
                new PaymentAmount(row.amountMinor, row.currency),
                new PaymentAmount(row.refundedAmountMinor, row.currency),
                row.status,
                row.reference,
                row.payerSubjectId,
                row.payerRealm,
                row.providerId,
                row.providerReference,
                refunds,
                row.createdAt,
                row.updatedAt);
    }

    private static PaymentRow mapRow(ResultSet row) throws SQLException {
        return new PaymentRow(
                row.getString("payment_id"),
                row.getString("tenant_id"),
                row.getLong("amount_minor"),
                trimCurrency(row.getString("currency")),
                row.getLong("refunded_amount_minor"),
                PaymentStatus.valueOf(row.getString("payment_status")),
                row.getString("domain_reference"),
                row.getString("payer_subject_id"),
                row.getString("payer_realm"),
                row.getString("provider_id"),
                row.getString("provider_reference"),
                row.getString("callback_notification_id"),
                row.getString("callback_notification_fingerprint"),
                instant(row.getTimestamp("created_at")),
                instant(row.getTimestamp("updated_at")));
    }

    private static String trimCurrency(String currency) {
        return currency == null ? null : currency.trim();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String fingerprint(CreatePaymentCommand command) {
        return sha256(
                command.amount().amountMinor()
                        + "|"
                        + command.amount().currency()
                        + "|"
                        + nullToEmpty(command.reference())
                        + "|"
                        + command.providerId()
                        + "|"
                        + nullToEmpty(command.payerSubjectId())
                        + "|"
                        + nullToEmpty(command.payerRealm()));
    }

    private static String callbackFingerprint(PaymentResultCallback callback) {
        return sha256(
                callback.resultingStatus().name() + "|" + nullToEmpty(callback.providerReference()));
    }

    private static String refundFingerprint(RefundCommand command) {
        return sha256(command.amount().amountMinor() + "|" + command.amount().currency());
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 required for payment fingerprint", ex);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record PaymentRow(
            String paymentId,
            String tenantId,
            long amountMinor,
            String currency,
            long refundedAmountMinor,
            PaymentStatus status,
            String reference,
            String payerSubjectId,
            String payerRealm,
            String providerId,
            String providerReference,
            String callbackNotificationId,
            String callbackFingerprint,
            Instant createdAt,
            Instant updatedAt) {}
}
