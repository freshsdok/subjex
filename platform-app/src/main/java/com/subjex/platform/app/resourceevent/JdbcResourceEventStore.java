package com.subjex.platform.app.resourceevent;

import com.subjex.platform.app.jdbc.PlatformTables;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcResourceEventStore — persist and aggregate generic share/open events already recorded.
 * 持久化并汇总已经记下的通用分享/打开事件。Does not invent counts for missing rows.
 * 没有行时不编造数字。
 */
public final class JdbcResourceEventStore {

    private final JdbcTemplate jdbc;

    public JdbcResourceEventStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void insert(ResourceEventRow row) {
        Objects.requireNonNull(row, "row");
        jdbc.update(
                """
                INSERT INTO resource_event (
                    event_id, tenant_id, resource_type, resource_id, event_type,
                    actor_subject_id, referrer, campaign,
                    utm_source, utm_medium, utm_campaign, utm_content, utm_term,
                    occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.eventId(),
                row.tenantId(),
                row.resourceType(),
                row.resourceId(),
                row.eventType(),
                row.actorSubjectId(),
                row.referrer(),
                row.campaign(),
                row.utmSource(),
                row.utmMedium(),
                row.utmCampaign(),
                row.utmContent(),
                row.utmTerm(),
                PlatformTables.timestamp(row.occurredAt()));
    }

    public Totals totals(String tenantId, Instant from, Instant to) {
        return jdbc.query(
                        """
                        SELECT
                            COALESCE(SUM(CASE WHEN event_type = 'share' THEN 1 ELSE 0 END), 0) AS shares,
                            COALESCE(SUM(CASE WHEN event_type = 'open' THEN 1 ELSE 0 END), 0) AS opens
                        FROM resource_event
                        WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at <= ?
                        """,
                        rs -> {
                            if (!rs.next()) {
                                return new Totals(0, 0);
                            }
                            return new Totals(rs.getLong("shares"), rs.getLong("opens"));
                        },
                        tenantId,
                        PlatformTables.timestamp(from),
                        PlatformTables.timestamp(to));
    }

    public List<ResourceCount> resourceCounts(String tenantId, Instant from, Instant to, int limit) {
        return jdbc.query(
                """
                SELECT resource_type, resource_id,
                       SUM(CASE WHEN event_type = 'share' THEN 1 ELSE 0 END) AS shares,
                       SUM(CASE WHEN event_type = 'open' THEN 1 ELSE 0 END) AS opens
                FROM resource_event
                WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at <= ?
                GROUP BY resource_type, resource_id
                ORDER BY (SUM(CASE WHEN event_type = 'share' THEN 1 ELSE 0 END)
                        + SUM(CASE WHEN event_type = 'open' THEN 1 ELSE 0 END)) DESC,
                         resource_type, resource_id
                LIMIT ?
                """,
                (row, i) -> new ResourceCount(
                        row.getString("resource_type"),
                        row.getString("resource_id"),
                        row.getLong("shares"),
                        row.getLong("opens")),
                tenantId,
                PlatformTables.timestamp(from),
                PlatformTables.timestamp(to),
                limit);
    }

    public List<DailyCount> dailyCounts(String tenantId, Instant from, Instant to) {
        return jdbc.query(
                """
                SELECT CAST(occurred_at AS DATE) AS bucket_day,
                       SUM(CASE WHEN event_type = 'share' THEN 1 ELSE 0 END) AS shares,
                       SUM(CASE WHEN event_type = 'open' THEN 1 ELSE 0 END) AS opens
                FROM resource_event
                WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at <= ?
                GROUP BY CAST(occurred_at AS DATE)
                ORDER BY bucket_day
                """,
                (row, i) -> new DailyCount(
                        toLocalDate(row.getObject("bucket_day")).toString(),
                        row.getLong("shares"),
                        row.getLong("opens")),
                tenantId,
                PlatformTables.timestamp(from),
                PlatformTables.timestamp(to));
    }

    public List<SharerCount> sharers(String tenantId, Instant from, Instant to, int limit) {
        return jdbc.query(
                """
                SELECT e.actor_subject_id, s.subject_name,
                       COUNT(*) AS shares
                FROM resource_event e
                LEFT JOIN subject s ON s.subject_id = e.actor_subject_id
                WHERE e.tenant_id = ?
                  AND e.event_type = 'share'
                  AND e.occurred_at >= ? AND e.occurred_at <= ?
                GROUP BY e.actor_subject_id, s.subject_name
                ORDER BY COUNT(*) DESC, e.actor_subject_id
                LIMIT ?
                """,
                (row, i) -> new SharerCount(
                        row.getString("actor_subject_id"),
                        row.getString("subject_name"),
                        row.getLong("shares")),
                tenantId,
                PlatformTables.timestamp(from),
                PlatformTables.timestamp(to),
                limit);
    }

    public List<CampaignCount> campaigns(String tenantId, Instant from, Instant to, int limit) {
        return jdbc.query(
                """
                SELECT campaign, utm_source, utm_medium, utm_campaign, utm_content, utm_term,
                       SUM(CASE WHEN event_type = 'share' THEN 1 ELSE 0 END) AS shares,
                       SUM(CASE WHEN event_type = 'open' THEN 1 ELSE 0 END) AS opens
                FROM resource_event
                WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at <= ?
                  AND (campaign IS NOT NULL
                       OR utm_source IS NOT NULL
                       OR utm_medium IS NOT NULL
                       OR utm_campaign IS NOT NULL
                       OR utm_content IS NOT NULL
                       OR utm_term IS NOT NULL)
                GROUP BY campaign, utm_source, utm_medium, utm_campaign, utm_content, utm_term
                ORDER BY (SUM(CASE WHEN event_type = 'share' THEN 1 ELSE 0 END)
                        + SUM(CASE WHEN event_type = 'open' THEN 1 ELSE 0 END)) DESC
                LIMIT ?
                """,
                (row, i) -> new CampaignCount(
                        row.getString("campaign"),
                        row.getString("utm_source"),
                        row.getString("utm_medium"),
                        row.getString("utm_campaign"),
                        row.getString("utm_content"),
                        row.getString("utm_term"),
                        row.getLong("shares"),
                        row.getLong("opens")),
                tenantId,
                PlatformTables.timestamp(from),
                PlatformTables.timestamp(to),
                limit);
    }

    /**
     * Counts by captured referrer only (blank referrer is omitted).
     * 只汇总已记下的来源页；没有来源页的事件不出现在这里。
     */
    public List<ReferrerCount> referrers(String tenantId, Instant from, Instant to, int limit) {
        return jdbc.query(
                """
                SELECT referrer,
                       SUM(CASE WHEN event_type = 'share' THEN 1 ELSE 0 END) AS shares,
                       SUM(CASE WHEN event_type = 'open' THEN 1 ELSE 0 END) AS opens
                FROM resource_event
                WHERE tenant_id = ? AND occurred_at >= ? AND occurred_at <= ?
                  AND referrer IS NOT NULL AND referrer <> ''
                GROUP BY referrer
                ORDER BY (SUM(CASE WHEN event_type = 'share' THEN 1 ELSE 0 END)
                        + SUM(CASE WHEN event_type = 'open' THEN 1 ELSE 0 END)) DESC,
                         referrer
                LIMIT ?
                """,
                (row, i) -> new ReferrerCount(
                        row.getString("referrer"),
                        row.getLong("shares"),
                        row.getLong("opens")),
                tenantId,
                PlatformTables.timestamp(from),
                PlatformTables.timestamp(to),
                limit);
    }

    private static LocalDate toLocalDate(Object raw) {
        if (raw instanceof LocalDate local) {
            return local;
        }
        if (raw instanceof Date date) {
            return date.toLocalDate();
        }
        throw new IllegalStateException("unexpected day type: " + (raw == null ? "null" : raw.getClass().getName()));
    }

    public record ResourceEventRow(
            String eventId,
            String tenantId,
            String resourceType,
            String resourceId,
            String eventType,
            String actorSubjectId,
            String referrer,
            String campaign,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String utmContent,
            String utmTerm,
            Instant occurredAt) {}

    public record Totals(long shares, long opens) {}

    public record ResourceCount(String resourceType, String resourceId, long shares, long opens) {}

    public record DailyCount(String day, long shares, long opens) {}

    public record SharerCount(String actorSubjectId, String displayName, long shares) {}

    public record CampaignCount(
            String campaign,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String utmContent,
            String utmTerm,
            long shares,
            long opens) {}

    public record ReferrerCount(String referrer, long shares, long opens) {}
}
