package com.subjex.platform.contract.inbox;

import java.time.Instant;
import java.util.Objects;

/**
 * InboxGroup — 站内信收件组（邮件列表式；非聊天室）。
 * <p>
 * Explicit mailing list for GROUP_SEND (ADR 0009). Not an org unit.
 * 显式收件名单，供 GROUP_SEND；不是组织节点。
 *
 * @param groupId primary key — 主键
 * @param tenantId owning tenant — 所属租户
 * @param name display name — 显示名
 * @param description optional description — 可选说明
 * @param realm OPERATOR / END_USER membership realm — 成员领域
 * @param createdBy creator subject id — 创建者主体
 * @param createdAt created instant — 创建时间
 * @param updatedAt updated instant — 更新时间
 */
public record InboxGroup(
        String groupId,
        String tenantId,
        String name,
        String description,
        InboxRecipientRealm realm,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public InboxGroup {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
