package com.subjex.platform.contract.inbox;

import java.util.Objects;

/**
 * InboxRecipient — 一个收件人：领域 + subject id。
 *
 * @param realm recipient realm — 收件人领域
 * @param subjectId recipient subject id — 收件人主体标识
 */
public record InboxRecipient(InboxRecipientRealm realm, String subjectId) {

    public InboxRecipient {
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(subjectId, "subjectId");
        subjectId = subjectId.trim();
        if (subjectId.isEmpty()) {
            throw new IllegalArgumentException("subjectId must not be blank");
        }
    }
}
