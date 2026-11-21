package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.identity.Account;
import com.subjex.platform.contract.identity.AccountState;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.identity.IdentityState;
import com.subjex.platform.contract.identity.Subject;
import com.subjex.platform.contract.identity.SubjectKind;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcEndUserAccountStore — WP2-1 JDBC persistence for end-user accounts and bindings.
 * 终端用户账号与绑定的 JDBC 实现。
 * <p>
 * Writes {@code account}, {@code subject}, {@code subject_identity}, {@code end_user_phone_binding},
 * and {@code end_user_idp_link} only. Never writes {@code operator_*} tables.
 * 只写身份三分与终端绑定表，绝不写入 {@code operator_*}。
 */
public final class JdbcEndUserAccountStore implements EndUserAccountStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public JdbcEndUserAccountStore(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EndUserRecord provision(String tenantId, String displayName) {
        String tid = EndUserAccountStore.requireExplicitBusinessTenantId(tenantId);
        String name = EndUserAccountStore.requireDisplayName(displayName);
        return transaction.execute(status -> {
            requireActiveBusinessTenant(tid);
            String accountId = "eu-acc-" + UUID.randomUUID();
            String subjectId = "eu-sub-" + UUID.randomUUID();
            String identityId = "eu-id-" + UUID.randomUUID();
            String loginName = "eu:" + accountId;
            jdbc.update(
                    "INSERT INTO account (account_id, login_name, account_state) VALUES (?, ?, ?)",
                    accountId,
                    loginName,
                    AccountState.ACTIVE.name());
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?, ?, ?)",
                    subjectId,
                    name,
                    SubjectKind.PERSON.name());
            jdbc.update(
                    """
                    INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    identityId,
                    accountId,
                    subjectId,
                    tid,
                    IdentityState.ACTIVE.name());
            return new EndUserRecord(
                    new Account(accountId, loginName, AccountState.ACTIVE),
                    new Subject(subjectId, name, SubjectKind.PERSON));
        });
    }

    @Override
    public Identity addIdentity(String accountId, String tenantId) {
        String aid = requireId(accountId, "accountId");
        String tid = EndUserAccountStore.requireExplicitBusinessTenantId(tenantId);
        return transaction.execute(status -> {
            requireActiveBusinessTenant(tid);
            EndUserRecord record = findByAccountId(aid)
                    .orElseThrow(() -> new IllegalArgumentException("end-user account not found: " + aid));
            if (findActiveIdentity(aid, tid).isPresent()) {
                throw new IllegalArgumentException("end-user already has an identity in tenant: " + tid);
            }
            String identityId = "eu-id-" + UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    identityId,
                    aid,
                    record.subject().subjectId(),
                    tid,
                    IdentityState.ACTIVE.name());
            return new Identity(identityId, aid, record.subject().subjectId(), tid, IdentityState.ACTIVE);
        });
    }

    @Override
    public void bindPhone(String accountId, String phone) {
        String aid = requireId(accountId, "accountId");
        String number = requirePhone(phone);
        requireAccount(aid);
        try {
            jdbc.update(
                    """
                    INSERT INTO end_user_phone_binding (binding_id, account_id, phone, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    "eu-ph-" + UUID.randomUUID(),
                    aid,
                    number,
                    timestamp(clock.instant()));
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("phone already bound", ex);
        }
    }

    @Override
    public void unbindPhone(String accountId, String phone) {
        String aid = requireId(accountId, "accountId");
        String number = requirePhone(phone);
        int removed = jdbc.update(
                "DELETE FROM end_user_phone_binding WHERE account_id = ? AND phone = ?",
                aid,
                number);
        if (removed == 0) {
            throw new IllegalArgumentException("phone not bound to this account");
        }
    }

    @Override
    public void bindIdp(String accountId, String providerId, String issuer, String idpSubject) {
        String aid = requireId(accountId, "accountId");
        String provider = requireId(providerId, "providerId");
        String iss = requireId(issuer, "issuer");
        String sub = requireId(idpSubject, "idpSubject");
        requireAccount(aid);
        try {
            jdbc.update(
                    """
                    INSERT INTO end_user_idp_link
                        (link_id, account_id, provider_id, issuer, idp_subject, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    "eu-idp-" + UUID.randomUUID(),
                    aid,
                    provider,
                    iss,
                    sub,
                    timestamp(clock.instant()));
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalArgumentException("idp subject already bound", ex);
        }
    }

    @Override
    public void unbindIdp(String accountId, String issuer, String idpSubject) {
        String aid = requireId(accountId, "accountId");
        String iss = requireId(issuer, "issuer");
        String sub = requireId(idpSubject, "idpSubject");
        int removed = jdbc.update(
                "DELETE FROM end_user_idp_link WHERE account_id = ? AND issuer = ? AND idp_subject = ?",
                aid,
                iss,
                sub);
        if (removed == 0) {
            throw new IllegalArgumentException("idp subject not bound to this account");
        }
    }

    @Override
    public int authenticatorCount(String accountId) {
        String aid = requireId(accountId, "accountId");
        Integer phones = jdbc.queryForObject(
                "SELECT COUNT(*) FROM end_user_phone_binding WHERE account_id = ?", Integer.class, aid);
        Integer links = jdbc.queryForObject(
                "SELECT COUNT(*) FROM end_user_idp_link WHERE account_id = ?", Integer.class, aid);
        return (phones == null ? 0 : phones) + (links == null ? 0 : links);
    }

    @Override
    public Optional<EndUserRecord> findByAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        return one(accountId.trim());
    }

    @Override
    public Optional<EndUserRecord> findByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        List<String> ids = jdbc.query(
                "SELECT account_id FROM end_user_phone_binding WHERE phone = ?",
                (row, n) -> row.getString("account_id"),
                phone.trim());
        return ids.isEmpty() ? Optional.empty() : one(ids.get(0));
    }

    @Override
    public Optional<EndUserRecord> findByIdp(String issuer, String idpSubject) {
        if (issuer == null || issuer.isBlank() || idpSubject == null || idpSubject.isBlank()) {
            return Optional.empty();
        }
        List<String> ids = jdbc.query(
                "SELECT account_id FROM end_user_idp_link WHERE issuer = ? AND idp_subject = ?",
                (row, n) -> row.getString("account_id"),
                issuer.trim(),
                idpSubject.trim());
        return ids.isEmpty() ? Optional.empty() : one(ids.get(0));
    }

    @Override
    public EndUserRecord updateDisplayName(String accountId, String displayName) {
        String aid = requireId(accountId, "accountId");
        String name = EndUserAccountStore.requireDisplayName(displayName);
        return transaction.execute(status -> {
            EndUserRecord record = findByAccountId(aid)
                    .orElseThrow(() -> new IllegalArgumentException("end-user account not found: " + aid));
            jdbc.update(
                    "UPDATE subject SET subject_name = ? WHERE subject_id = ?",
                    name,
                    record.subject().subjectId());
            return new EndUserRecord(
                    record.account(),
                    new Subject(record.subject().subjectId(), name, record.subject().subjectKind()));
        });
    }

    @Override
    public Optional<Identity> findActiveIdentity(String accountId, String tenantId) {
        if (accountId == null || accountId.isBlank() || tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        List<Identity> rows = jdbc.query(
                """
                SELECT identity_id, account_id, subject_id, tenant_id, identity_state
                FROM subject_identity
                WHERE account_id = ? AND tenant_id = ? AND identity_state = ?
                """,
                (row, n) -> new Identity(
                        row.getString("identity_id"),
                        row.getString("account_id"),
                        row.getString("subject_id"),
                        row.getString("tenant_id"),
                        IdentityState.valueOf(row.getString("identity_state"))),
                accountId.trim(),
                tenantId.trim(),
                IdentityState.ACTIVE.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Optional<EndUserRecord> one(String accountId) {
        List<EndUserRecord> rows = jdbc.query(
                """
                SELECT a.account_id, a.login_name, a.account_state,
                       s.subject_id, s.subject_name, s.subject_kind
                FROM account a
                JOIN subject_identity si ON si.account_id = a.account_id
                JOIN subject s ON s.subject_id = si.subject_id
                WHERE a.account_id = ?
                """,
                (row, n) -> new EndUserRecord(
                        new Account(
                                row.getString("account_id"),
                                row.getString("login_name"),
                                AccountState.valueOf(row.getString("account_state"))),
                        new Subject(
                                row.getString("subject_id"),
                                row.getString("subject_name"),
                                SubjectKind.valueOf(row.getString("subject_kind")))),
                accountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private void requireAccount(String accountId) {
        if (findByAccountId(accountId).isEmpty()) {
            throw new IllegalArgumentException("end-user account not found: " + accountId);
        }
    }

    /**
     * Tenant must exist, be ACTIVE, and not be the reserved operator tenant.
     * 租户必须已存在、为 ACTIVE，且不是保留操作员租户。
     */
    @Override
    public void requireActiveBusinessTenant(String tenantId) {
        List<String> states = jdbc.query(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> row.getString("tenant_state"),
                tenantId);
        if (states.isEmpty()) {
            throw new TenantNotFoundException(tenantId);
        }
        String state = states.get(0) == null ? "" : states.get(0).trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(state)) {
            throw new TenantDisabledException(tenantId);
        }
    }

    private static String requirePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("phone is required");
        }
        return phone.trim();
    }

    private static String requireId(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static java.sql.Timestamp timestamp(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }
}
