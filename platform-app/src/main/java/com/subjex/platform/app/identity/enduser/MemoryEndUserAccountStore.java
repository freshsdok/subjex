package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.identity.Account;
import com.subjex.platform.contract.identity.AccountState;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.identity.IdentityState;
import com.subjex.platform.contract.identity.Subject;
import com.subjex.platform.contract.identity.SubjectKind;
import com.subjex.platform.contract.tenant.TenantState;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MemoryEndUserAccountStore — in-process WP2-1 store for unit tests (same rules as JDBC).
 * 进程内实现，规则与 JDBC 相同，供单测使用。
 */
public final class MemoryEndUserAccountStore implements EndUserAccountStore {

    private final Map<String, TenantState> tenants = new ConcurrentHashMap<>();
    private final Map<String, EndUserRecord> accounts = new ConcurrentHashMap<>();
    private final Map<String, Identity> identities = new ConcurrentHashMap<>();
    private final Map<String, String> phoneToAccount = new ConcurrentHashMap<>();
    private final Map<String, String> accountToPhone = new ConcurrentHashMap<>();
    private final Map<String, String> idpToAccount = new ConcurrentHashMap<>();
    private final Map<String, String> accountToIdp = new ConcurrentHashMap<>();

    /**
     * Register a tenant the store may bind identities into (tests / in-process only).
     * 登记可供绑定身份的租户（仅测试/进程内）。
     */
    public void registerTenant(String tenantId, TenantState state) {
        if (tenantId == null || tenantId.isBlank() || state == null) {
            throw new IllegalArgumentException("tenant id and state are required");
        }
        tenants.put(tenantId.trim(), state);
    }

    @Override
    public EndUserRecord provision(String tenantId, String displayName) {
        String tid = EndUserAccountStore.requireExplicitBusinessTenantId(tenantId);
        String name = EndUserAccountStore.requireDisplayName(displayName);
        requireActiveBusinessTenant(tid);
        String accountId = "eu-acc-" + UUID.randomUUID();
        String subjectId = "eu-sub-" + UUID.randomUUID();
        String identityId = "eu-id-" + UUID.randomUUID();
        String loginName = "eu:" + accountId;
        EndUserRecord record = new EndUserRecord(
                new Account(accountId, loginName, AccountState.ACTIVE),
                new Subject(subjectId, name, SubjectKind.PERSON));
        accounts.put(accountId, record);
        identities.put(identityKey(accountId, tid),
                new Identity(identityId, accountId, subjectId, tid, IdentityState.ACTIVE));
        return record;
    }

    @Override
    public Identity addIdentity(String accountId, String tenantId) {
        String aid = requireId(accountId, "accountId");
        String tid = EndUserAccountStore.requireExplicitBusinessTenantId(tenantId);
        requireActiveBusinessTenant(tid);
        EndUserRecord record = accounts.get(aid);
        if (record == null) {
            throw new IllegalArgumentException("end-user account not found: " + aid);
        }
        String key = identityKey(aid, tid);
        if (identities.containsKey(key)) {
            throw new IllegalArgumentException("end-user already has an identity in tenant: " + tid);
        }
        Identity identity = new Identity(
                "eu-id-" + UUID.randomUUID(),
                aid,
                record.subject().subjectId(),
                tid,
                IdentityState.ACTIVE);
        identities.put(key, identity);
        return identity;
    }

    @Override
    public void bindPhone(String accountId, String phone) {
        String aid = requireId(accountId, "accountId");
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("phone is required");
        }
        String number = phone.trim();
        if (!accounts.containsKey(aid)) {
            throw new IllegalArgumentException("end-user account not found: " + aid);
        }
        synchronized (this) {
            String existingAccount = phoneToAccount.get(number);
            if (existingAccount != null) {
                throw new IllegalArgumentException("phone already bound");
            }
            if (accountToPhone.containsKey(aid)) {
                throw new IllegalArgumentException("phone already bound");
            }
            phoneToAccount.put(number, aid);
            accountToPhone.put(aid, number);
        }
    }

    @Override
    public void unbindPhone(String accountId, String phone) {
        String aid = requireId(accountId, "accountId");
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("phone is required");
        }
        String number = phone.trim();
        synchronized (this) {
            String bound = accountToPhone.get(aid);
            if (bound == null || !bound.equals(number) || !aid.equals(phoneToAccount.get(number))) {
                throw new IllegalArgumentException("phone not bound to this account");
            }
            accountToPhone.remove(aid);
            phoneToAccount.remove(number);
        }
    }

    @Override
    public void bindIdp(String accountId, String providerId, String issuer, String idpSubject) {
        String aid = requireId(accountId, "accountId");
        requireId(providerId, "providerId");
        String iss = requireId(issuer, "issuer");
        String sub = requireId(idpSubject, "idpSubject");
        if (!accounts.containsKey(aid)) {
            throw new IllegalArgumentException("end-user account not found: " + aid);
        }
        String idpKey = idpKey(iss, sub);
        synchronized (this) {
            if (idpToAccount.containsKey(idpKey) || accountToIdp.containsKey(aid)) {
                throw new IllegalArgumentException("idp subject already bound");
            }
            idpToAccount.put(idpKey, aid);
            accountToIdp.put(aid, idpKey);
        }
    }

    @Override
    public void unbindIdp(String accountId, String issuer, String idpSubject) {
        String aid = requireId(accountId, "accountId");
        String iss = requireId(issuer, "issuer");
        String sub = requireId(idpSubject, "idpSubject");
        String key = idpKey(iss, sub);
        synchronized (this) {
            String bound = accountToIdp.get(aid);
            if (bound == null || !bound.equals(key) || !aid.equals(idpToAccount.get(key))) {
                throw new IllegalArgumentException("idp subject not bound to this account");
            }
            accountToIdp.remove(aid);
            idpToAccount.remove(key);
        }
    }

    @Override
    public int authenticatorCount(String accountId) {
        String aid = requireId(accountId, "accountId");
        int phones = accountToPhone.containsKey(aid) ? 1 : 0;
        int links = accountToIdp.containsKey(aid) ? 1 : 0;
        return phones + links;
    }

    @Override
    public Optional<EndUserRecord> findByAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(accounts.get(accountId.trim()));
    }

    @Override
    public Optional<EndUserRecord> findByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        String accountId = phoneToAccount.get(phone.trim());
        return accountId == null ? Optional.empty() : Optional.ofNullable(accounts.get(accountId));
    }

    @Override
    public Optional<EndUserRecord> findByIdp(String issuer, String idpSubject) {
        if (issuer == null || issuer.isBlank() || idpSubject == null || idpSubject.isBlank()) {
            return Optional.empty();
        }
        String accountId = idpToAccount.get(idpKey(issuer.trim(), idpSubject.trim()));
        return accountId == null ? Optional.empty() : Optional.ofNullable(accounts.get(accountId));
    }

    @Override
    public EndUserRecord updateDisplayName(String accountId, String displayName) {
        String aid = requireId(accountId, "accountId");
        String name = EndUserAccountStore.requireDisplayName(displayName);
        EndUserRecord record = accounts.get(aid);
        if (record == null) {
            throw new IllegalArgumentException("end-user account not found: " + aid);
        }
        EndUserRecord updated = new EndUserRecord(
                record.account(),
                new Subject(record.subject().subjectId(), name, record.subject().subjectKind()));
        accounts.put(aid, updated);
        return updated;
    }

    @Override
    public Optional<Identity> findActiveIdentity(String accountId, String tenantId) {
        if (accountId == null || accountId.isBlank() || tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        Identity identity = identities.get(identityKey(accountId.trim(), tenantId.trim()));
        if (identity == null || identity.identityState() != IdentityState.ACTIVE) {
            return Optional.empty();
        }
        return Optional.of(identity);
    }

    @Override
    public void requireActiveBusinessTenant(String tenantId) {
        TenantState state = tenants.get(tenantId);
        if (state == null) {
            throw new TenantNotFoundException(tenantId);
        }
        if (state != TenantState.ACTIVE) {
            throw new TenantDisabledException(tenantId);
        }
    }

    private static String requireId(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }

    private static String identityKey(String accountId, String tenantId) {
        return accountId + "\0" + tenantId;
    }

    private static String idpKey(String issuer, String idpSubject) {
        return issuer + "\0" + idpSubject;
    }
}
