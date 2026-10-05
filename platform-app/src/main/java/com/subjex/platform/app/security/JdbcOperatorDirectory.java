package com.subjex.platform.app.security;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * JdbcOperatorDirectory — 操作员名录：按登录名找到账号、口令摘要、平台身份和权限。
 * <p>
 * account → operator_credential gives the password hash. account → subject_identity in tenant {@code platform}
 * gives the acting identity and subject. subject_role → role_permission gives the permission names.
 * The operator is enabled only when the account is ACTIVE and that identity is ACTIVE.
 * account → operator_credential 给出口令摘要。account → 租户 {@code platform} 里的 subject_identity 给出行动身份和主体。
 * subject_role → role_permission 给出权限名。只有账号和该身份都是 ACTIVE 时，操作员才可用。
 */
public final class JdbcOperatorDirectory implements UserDetailsService {

    /** Reserved tenant for operator identities — 操作员身份所在的保留租户。 */
    public static final String OPERATOR_TENANT = "platform";

    private final JdbcTemplate jdbc;

    public JdbcOperatorDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String loginName) {
        if (loginName == null || loginName.isBlank()) {
            throw new UsernameNotFoundException("operator is unknown");
        }
        List<SignIn> found = jdbc.query(
                """
                SELECT a.login_name, a.account_state, c.password_hash,
                       i.identity_id, i.subject_id, i.identity_state
                FROM account a
                JOIN operator_credential c ON c.account_id = a.account_id
                JOIN subject_identity i ON i.account_id = a.account_id AND i.tenant_id = ?
                WHERE a.login_name = ?
                """,
                (row, rowNumber) -> new SignIn(
                        row.getString("login_name"),
                        row.getString("account_state"),
                        row.getString("password_hash"),
                        row.getString("identity_id"),
                        row.getString("subject_id"),
                        row.getString("identity_state")),
                OPERATOR_TENANT,
                loginName);
        if (found.size() != 1) {
            // Zero rows is unknown; two rows is ambiguous. Both refuse.
            // 零行是不认识；两行是有歧义。两种都拒绝。
            throw new UsernameNotFoundException("operator is unknown");
        }
        SignIn signIn = found.get(0);
        Set<String> permissions = new HashSet<>(jdbc.queryForList(
                """
                SELECT DISTINCT rp.permission_name
                FROM subject_role sr
                JOIN role_permission rp ON rp.role_name = sr.role_name
                WHERE sr.subject_id = ?
                """,
                String.class,
                signIn.subjectId()));
        boolean active = "ACTIVE".equals(signIn.accountState()) && "ACTIVE".equals(signIn.identityState());
        return new OperatorPrincipal(
                signIn.loginName(), signIn.passwordHash(), signIn.identityId(), signIn.subjectId(), permissions, active);
    }

    private record SignIn(
            String loginName,
            String accountState,
            String passwordHash,
            String identityId,
            String subjectId,
            String identityState) {
    }
}
