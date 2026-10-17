package com.subjex.platform.contract.inbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * InboxGroupPort — 站内信收件组端口：CRUD + 成员 + 展开（供 GROUP_SEND）。
 * <p>
 * Tenant fail-closed; no cross-tenant group read/write (ADR 0009).
 * 租户失败关闭；禁止跨租户读写组。
 */
public interface InboxGroupPort {

    /**
     * Create a mailing group — 创建收件组.
     */
    InboxGroup create(
            String tenantId,
            String name,
            String descriptionOrNull,
            InboxRecipientRealm realm,
            String createdBy,
            Instant now);

    /**
     * Get group in tenant or empty — 租户内取组，否则 empty.
     */
    Optional<InboxGroup> get(String tenantId, String groupId);

    /**
     * List groups for tenant + realm — 按租户与领域列组.
     */
    List<InboxGroup> list(String tenantId, InboxRecipientRealm realm);

    /**
     * Rename / redescribe; returns false if missing — 改名/说明；不存在返回 false.
     */
    boolean update(String tenantId, String groupId, String name, String descriptionOrNull, Instant now);

    /**
     * Delete group and members; returns false if missing — 删组及成员；不存在返回 false.
     */
    boolean delete(String tenantId, String groupId);

    /**
     * Add member (idempotent). Returns false if group missing — 加成员（幂等）；组不存在返回 false.
     */
    boolean addMember(
            String tenantId,
            String groupId,
            InboxRecipientRealm memberRealm,
            String memberSubjectId,
            Instant addedAt);

    /**
     * Remove member; returns false if group or member missing — 移除成员；组或成员不存在返回 false.
     */
    boolean removeMember(
            String tenantId, String groupId, InboxRecipientRealm memberRealm, String memberSubjectId);

    /**
     * List members — 列成员.
     */
    List<InboxGroupMember> listMembers(String tenantId, String groupId);

    /**
     * Expand to recipients for fan-out (deduped) — 展开为收件人（去重）.
     */
    List<InboxRecipient> expandMembers(String tenantId, String groupId);
}
