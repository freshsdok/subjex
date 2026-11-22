package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * EndUserDirectoryService — tenant-scoped list of existing end-user identity rows.
 * 按租户列出已经存在的终端用户身份行。
 * <p>
 * Hangs on {@code subject_identity} plus {@code account} / {@code subject} and the existing
 * phone / IdP binding tables. Does not invent last-login, and does not disable or unlink accounts.
 * Phone numbers and IdP subjects are masked or omitted. Reserved tenant {@code platform} is refused.
 * 挂在已有身份表与手机号/IdP 绑定表上。不编造最后登录时间，也不停用或解绑账号。
 * 手机号做遮盖，IdP 主体编号不返回。拒绝保留租户 {@code platform}。
 */
public final class EndUserDirectoryService {

    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 500;

    private final JdbcTemplate jdbc;
    private final OperatorTenantAccess tenantAccess;

    public EndUserDirectoryService(JdbcTemplate jdbc, OperatorTenantAccess tenantAccess) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
    }

    public List<DirectoryRow> list(OperatorPrincipal operator, String tenantId, String query, Integer limit) {
        Objects.requireNonNull(operator, "operator");
        String tid = requireBusinessTenant(tenantId);
        tenantAccess.requireGranted(operator, tid);
        String needle = query == null ? "" : query.trim();
        String like = needle.isEmpty() ? null : "%" + escapeLike(needle).toLowerCase() + "%";
        String exact = needle.isEmpty() ? null : needle;
        int cap = clampLimit(limit);
        return jdbc.query(
                """
                SELECT si.tenant_id, si.identity_state,
                       a.account_id, a.account_state,
                       s.subject_id, s.subject_name,
                       p.phone, l.provider_id, l.issuer
                FROM subject_identity si
                INNER JOIN account a ON a.account_id = si.account_id
                INNER JOIN subject s ON s.subject_id = si.subject_id
                LEFT JOIN end_user_phone_binding p ON p.account_id = a.account_id
                LEFT JOIN end_user_idp_link l ON l.account_id = a.account_id
                WHERE si.tenant_id = ?
                  AND (? IS NULL
                       OR si.subject_id = ?
                       OR a.account_id = ?
                       OR LOWER(s.subject_name) LIKE ? ESCAPE '\\'
                       OR p.phone = ?)
                ORDER BY s.subject_name, s.subject_id
                LIMIT ?
                """,
                (row, i) -> new DirectoryRow(
                        row.getString("tenant_id"),
                        row.getString("subject_id"),
                        row.getString("account_id"),
                        row.getString("subject_name"),
                        row.getString("account_state"),
                        row.getString("identity_state"),
                        maskPhone(row.getString("phone")),
                        idpBinding(row.getString("provider_id"), row.getString("issuer"))),
                tid,
                exact,
                exact,
                exact,
                like,
                exact,
                cap);
    }

    static String requireBusinessTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
        String tid = tenantId.trim();
        if (EndUserAccountStore.RESERVED_TENANT.equals(tid)) {
            throw new IllegalArgumentException("reserved tenant platform is not an end-user home");
        }
        return tid;
    }

    static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * Mask a stored phone so operators can recognize it without seeing the full number.
     * 遮盖已存手机号，便于辨认又不必看到完整号码。
     */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String value = phone.trim();
        int length = value.length();
        if (length <= 4) {
            return "*".repeat(length);
        }
        int head = length >= 11 ? 3 : 2;
        int tail = Math.min(4, length - head - 1);
        if (tail < 1) {
            tail = 1;
        }
        int stars = length - head - tail;
        if (stars < 1) {
            stars = 1;
            tail = length - head - stars;
        }
        return value.substring(0, head) + "*".repeat(stars) + value.substring(length - tail);
    }

    private static IdpBinding idpBinding(String providerId, String issuer) {
        if ((providerId == null || providerId.isBlank()) && (issuer == null || issuer.isBlank())) {
            return null;
        }
        return new IdpBinding(
                providerId == null || providerId.isBlank() ? null : providerId.trim(),
                issuer == null || issuer.isBlank() ? null : issuer.trim());
    }

    private static String escapeLike(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * One end-user membership in a tenant. Never includes token hashes, raw phones, or IdP subjects.
     * 该租户下的一条终端成员。不含令牌摘要、完整手机号或 IdP 主体编号。
     */
    public record DirectoryRow(
            String tenantId,
            String subjectId,
            String accountId,
            String displayName,
            String accountState,
            String membershipState,
            String phoneMasked,
            IdpBinding idp) {}

    /** Bound IdP without the subject identifier — 已绑定的 IdP，不含主体编号。 */
    public record IdpBinding(String providerId, String issuer) {}
}
