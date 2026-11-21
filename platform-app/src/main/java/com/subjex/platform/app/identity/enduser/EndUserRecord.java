package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.contract.identity.Account;
import com.subjex.platform.contract.identity.Subject;
import java.util.Objects;

/**
 * EndUserRecord — an end-user account plus its Subject (ADR 0014 / WP2-1).
 * 终端用户账号及其主体。
 * <p>
 * Tenant membership is a separate {@code subject_identity} row, never implied here.
 * 租户成员关系是单独的 {@code subject_identity} 行，不在此隐含。
 */
public record EndUserRecord(Account account, Subject subject) {

    public EndUserRecord {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(subject, "subject");
    }
}
