package com.subjex.platform.app.admin;

import com.subjex.platform.app.identity.enduser.JdbcEndUserTokenStore;
import com.subjex.platform.app.security.JdbcOperatorTokenStore;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SessionListService — current logins from existing refresh-token families (no new session product).
 * 当前登录列表：挂在已有刷新令牌族上，不另做一套会话产品。
 * <p>
 * A row is one non-revoked, non-expired refresh token (the current member of a rotating family).
 * Stored fields are login/display name, optional user-agent and IP captured at issue/refresh,
 * and expiry. There is no device name, issued-at, or last-used column — this list does not invent them.
 * 一行即一条未吊销且未过期的刷新令牌（轮换族中的当前成员）。库里有登录名/显示名、签发或刷新时记录的
 * 浏览器与网络地址、以及到期时间。没有设备名、签发时间或最后使用时间，本列表不加造这些字段。
 */
public final class SessionListService {

    public static final String REALM_OPERATOR = "operator";
    public static final String REALM_END_USER = "end-user";
    public static final String AUDIT_REVOKE_OPERATOR = "session.revoke.operator";
    public static final String AUDIT_REVOKE_END_USER = "session.revoke.end-user";
    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 500;

    private final JdbcTemplate jdbc;
    private final JdbcOperatorTokenStore operatorTokens;
    private final JdbcEndUserTokenStore endUserTokens;
    private final OperatorTenantAccess tenantAccess;
    private final OperatorActionAudit audit;
    private final Clock clock;

    public SessionListService(
            JdbcTemplate jdbc,
            JdbcOperatorTokenStore operatorTokens,
            JdbcEndUserTokenStore endUserTokens,
            OperatorTenantAccess tenantAccess,
            OperatorActionAudit audit,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.operatorTokens = Objects.requireNonNull(operatorTokens, "operatorTokens");
        this.endUserTokens = Objects.requireNonNull(endUserTokens, "endUserTokens");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public List<SessionRow> listOperators(Integer limit) {
        int cap = clampLimit(limit);
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.query(
                """
                SELECT r.family_id, r.subject_id, r.expires_at, r.user_agent, r.client_ip, a.login_name
                FROM operator_refresh_token r
                INNER JOIN account a ON a.account_id = r.account_id
                WHERE r.revoked_at IS NULL AND r.expires_at > ?
                ORDER BY r.expires_at DESC
                LIMIT ?
                """,
                (row, i) -> new SessionRow(
                        row.getString("family_id"),
                        REALM_OPERATOR,
                        row.getString("subject_id"),
                        row.getString("login_name"),
                        null,
                        row.getString("user_agent"),
                        row.getString("client_ip"),
                        row.getTimestamp("expires_at").toInstant()),
                now,
                cap);
    }

    public List<SessionRow> listEndUsers(OperatorPrincipal operator, String tenantId, Integer limit) {
        Objects.requireNonNull(operator, "operator");
        String tid = requireTenantId(tenantId);
        tenantAccess.requireGranted(operator, tid);
        int cap = clampLimit(limit);
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.query(
                """
                SELECT r.family_id, r.subject_id, r.expires_at, r.user_agent, r.client_ip,
                       s.subject_name, si.tenant_id
                FROM end_user_refresh_token r
                INNER JOIN subject_identity si ON si.subject_id = r.subject_id
                INNER JOIN subject s ON s.subject_id = r.subject_id
                WHERE si.tenant_id = ? AND r.revoked_at IS NULL AND r.expires_at > ?
                ORDER BY r.expires_at DESC
                LIMIT ?
                """,
                (row, i) -> new SessionRow(
                        row.getString("family_id"),
                        REALM_END_USER,
                        row.getString("subject_id"),
                        row.getString("subject_name"),
                        row.getString("tenant_id"),
                        row.getString("user_agent"),
                        row.getString("client_ip"),
                        row.getTimestamp("expires_at").toInstant()),
                tid,
                now,
                cap);
    }

    public boolean revokeOperatorFamily(OperatorPrincipal operator, String familyId) {
        Objects.requireNonNull(operator, "operator");
        boolean revoked = operatorTokens.revokeFamilyById(familyId);
        if (revoked) {
            audit.record(operator, AUDIT_REVOKE_OPERATOR, familyId.trim(), AuditOutcome.ALLOWED);
        }
        return revoked;
    }

    public boolean revokeEndUserFamily(OperatorPrincipal operator, String tenantId, String familyId) {
        Objects.requireNonNull(operator, "operator");
        String tid = requireTenantId(tenantId);
        tenantAccess.requireGranted(operator, tid);
        boolean revoked = endUserTokens.revokeFamilyByIdInTenant(familyId, tid);
        if (revoked) {
            audit.record(operator, AUDIT_REVOKE_END_USER, tid + ":" + familyId.trim(), AuditOutcome.ALLOWED);
        }
        return revoked;
    }

    private static String requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        return tenantId.trim();
    }

    static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * One current refresh-token family. Never includes token hashes or raw tokens.
     * 一条当前刷新令牌族。从不包含令牌摘要或明文令牌。
     */
    public record SessionRow(
            String familyId,
            String realm,
            String subjectId,
            String displayName,
            String tenantId,
            String userAgent,
            String clientIp,
            Instant expiresAt) {}
}
