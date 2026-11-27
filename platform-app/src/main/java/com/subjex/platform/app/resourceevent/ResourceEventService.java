package com.subjex.platform.app.resourceevent;

import com.subjex.platform.app.identity.enduser.EndUserAccountStore;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.resourceevent.JdbcResourceEventStore.CampaignCount;
import com.subjex.platform.app.resourceevent.JdbcResourceEventStore.DailyCount;
import com.subjex.platform.app.resourceevent.JdbcResourceEventStore.ReferrerCount;
import com.subjex.platform.app.resourceevent.JdbcResourceEventStore.ResourceCount;
import com.subjex.platform.app.resourceevent.JdbcResourceEventStore.ResourceEventRow;
import com.subjex.platform.app.resourceevent.JdbcResourceEventStore.SharerCount;
import com.subjex.platform.app.resourceevent.JdbcResourceEventStore.Totals;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.app.task.RateLimitExceeded;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ResourceEventService — record and summarise generic share/open events (no marketing suite).
 * 记录并汇总通用的分享/打开事件（不是营销套件）。
 * <p>
 * Time is the moment the platform accepts the event. Campaign and UTM are stored only if sent.
 * 时间为平台接受该事件的时刻。活动与 UTM 仅在客户端带上时才保存。
 */
public final class ResourceEventService {

    public static final String EVENT_SHARE = "share";
    public static final String EVENT_OPEN = "open";
    public static final String RATE_ACTION = "record-resource-event";
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(30);
    public static final Duration MAX_WINDOW = Duration.ofDays(366);
    public static final int MAX_ROWS = 200;

    private static final Pattern TYPE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9._:-]{0,63}$");
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final JdbcTemplate jdbc;
    private final JdbcResourceEventStore store;
    private final OperatorTenantAccess tenantAccess;
    private final RateLimitPort rateLimit;
    private final Clock clock;

    public ResourceEventService(
            JdbcTemplate jdbc,
            JdbcResourceEventStore store,
            OperatorTenantAccess tenantAccess,
            RateLimitPort rateLimit,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.store = Objects.requireNonNull(store, "store");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.rateLimit = Objects.requireNonNull(rateLimit, "rateLimit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AcceptedEvent record(Object principal, String tenantId, IngestRequest request) {
        Objects.requireNonNull(request, "request");
        String tid = requireActiveBusinessTenant(firstNonBlank(tenantId, request.tenantId()));
        if (!rateLimit.permit(tid, RATE_ACTION)) {
            throw new RateLimitExceeded();
        }
        String eventType = normalizeEventType(request.eventType());
        String resourceType = requireToken(request.resourceType(), TYPE_PATTERN, "resourceType");
        String resourceId = requireToken(request.resourceId(), ID_PATTERN, "resourceId");
        String actor = principal instanceof EndUserPrincipal endUser ? endUser.subjectId() : null;
        Instant now = clock.instant();
        String eventId = UUID.randomUUID().toString();
        store.insert(new ResourceEventRow(
                eventId,
                tid,
                resourceType,
                resourceId,
                eventType,
                actor,
                trimTo(request.referrer(), 512),
                optionalAttr(request.campaign()),
                optionalAttr(request.utmSource()),
                optionalAttr(request.utmMedium()),
                optionalAttr(request.utmCampaign()),
                optionalAttr(request.utmContent()),
                optionalAttr(request.utmTerm()),
                now));
        return new AcceptedEvent(eventId, tid, eventType, resourceType, resourceId, now);
    }

    public SummaryDocument summarise(OperatorPrincipal operator, String tenantId, Instant from, Instant to) {
        Objects.requireNonNull(operator, "operator");
        String tid = requireBusinessTenant(tenantId);
        tenantAccess.requireGranted(operator, tid);
        Instant now = clock.instant();
        Instant end = to == null ? now : to;
        Instant start = from == null ? end.minus(DEFAULT_WINDOW) : from;
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("window must be at most 366 days");
        }
        Totals totals = store.totals(tid, start, end);
        List<CampaignCount> campaigns = store.campaigns(tid, start, end, MAX_ROWS);
        List<ReferrerShare> referrers = referrerShares(store.referrers(tid, start, end, MAX_ROWS), totals);
        return new SummaryDocument(
                tid,
                start,
                end,
                totals,
                store.resourceCounts(tid, start, end, MAX_ROWS),
                store.dailyCounts(tid, start, end),
                store.sharers(tid, start, end, MAX_ROWS),
                campaigns,
                referrers);
    }

    /**
     * Attach each captured referrer's share-of the tenant totals (raw counts stay in the row).
     * 为已捕获的来源页附上占该租户合计的份额（行内仍保留原始次数）。
     * Events with no referrer are not listed; they remain in the totals.
     * 没有来源页的事件不单独成行，但仍计入合计。
     */
    static List<ReferrerShare> referrerShares(List<ReferrerCount> rows, Totals totals) {
        long shareTotal = totals == null ? 0L : totals.shares();
        long openTotal = totals == null ? 0L : totals.opens();
        return rows.stream()
                .map(row -> new ReferrerShare(
                        row.referrer(),
                        row.shares(),
                        row.opens(),
                        shareOf(row.shares(), shareTotal),
                        shareOf(row.opens(), openTotal)))
                .toList();
    }

    /**
     * Whole-number share of {@code total}, in parts per ten thousand, or 0 when total is 0.
     * 占 {@code total} 的万分比整数；合计为 0 时为 0。避免把浮点误差写进接口。
     */
    static int shareOf(long part, long total) {
        if (total <= 0L || part <= 0L) {
            return 0;
        }
        return (int) Math.min(10_000L, (part * 10_000L) / total);
    }

    private String requireActiveBusinessTenant(String tenantId) {
        String tid = requireBusinessTenant(tenantId);
        List<String> states = jdbc.queryForList(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?", String.class, tid);
        if (states.isEmpty()) {
            throw new TenantNotFoundException(tid);
        }
        if (!"ACTIVE".equals(states.get(0))) {
            throw new TenantDisabledException(tid);
        }
        return tid;
    }

    static String requireBusinessTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String tid = tenantId.trim();
        if (EndUserAccountStore.RESERVED_TENANT.equals(tid)) {
            throw new IllegalArgumentException("reserved tenant platform is not a resource-event home");
        }
        return tid;
    }

    static String normalizeEventType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("view".equals(value)) {
            return EVENT_OPEN;
        }
        if (EVENT_SHARE.equals(value) || EVENT_OPEN.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("eventType must be share or open");
    }

    private static String requireToken(String raw, Pattern pattern, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String value = raw.trim();
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is not a generic token");
        }
        return value;
    }

    private static String optionalAttr(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > 128) {
            value = value.substring(0, 128);
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("campaign or UTM field has a control character");
            }
        }
        return value;
    }

    private static String trimTo(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left.trim();
        }
        return right;
    }

    public record IngestRequest(
            String tenantId,
            String resourceType,
            String resourceId,
            String eventType,
            String referrer,
            String campaign,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String utmContent,
            String utmTerm) {}

    public record AcceptedEvent(
            String eventId,
            String tenantId,
            String eventType,
            String resourceType,
            String resourceId,
            Instant occurredAt) {}

    public record SummaryDocument(
            String tenantId,
            Instant from,
            Instant to,
            Totals totals,
            List<ResourceCount> resources,
            List<DailyCount> daily,
            List<SharerCount> sharers,
            List<CampaignCount> campaigns,
            List<ReferrerShare> referrers) {}

    /**
     * One captured referrer plus its share of tenant totals, in parts per ten thousand.
     * 一条已捕获来源页，以及占该租户合计的万分比。
     * {@code shareOfShares} 2500 means 25% of recorded shares in the window.
     * {@code shareOfShares} 为 2500 表示占本时段已记录分享的 25%。
     */
    public record ReferrerShare(
            String referrer, long shares, long opens, int shareOfShares, int shareOfOpens) {}
}
