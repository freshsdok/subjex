package com.subjex.platform.contract.inbox;

import java.time.Instant;
import java.util.Objects;

/**
 * InboxGroupMember — 站内信组成员.
 *
 * @param groupId group id — 组 id
 * @param tenantId owning tenant — 所属租户
 * @param memberRealm member realm — 成员领域
 * @param memberSubjectId member subject — 成员主体
 * @param addedAt added instant — 加入时间
 */
public record InboxGroupMember(
        String groupId,
        String tenantId,
        InboxRecipientRealm memberRealm,
        String memberSubjectId,
        Instant addedAt) {

    public InboxGroupMember {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(memberRealm, "memberRealm");
        Objects.requireNonNull(memberSubjectId, "memberSubjectId");
        Objects.requireNonNull(addedAt, "addedAt");
    }
}
