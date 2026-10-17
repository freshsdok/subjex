package com.subjex.platform.app.inbox;

import com.subjex.platform.contract.inbox.InboxGroup;
import com.subjex.platform.contract.inbox.InboxGroupMember;
import com.subjex.platform.contract.inbox.InboxGroupPort;
import com.subjex.platform.contract.inbox.InboxRecipient;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcInboxGroupPort — JDBC 站内信收件组（ADR 0009 IM-A1）。
 * <p>
 * Tenant fail-closed; delete cascades members in one TX.
 * 租户失败关闭；删组同事务清成员。
 */
public final class JdbcInboxGroupPort implements InboxGroupPort {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcInboxGroupPort(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    @Override
    public InboxGroup create(
            String tenantId,
            String name,
            String descriptionOrNull,
            InboxRecipientRealm realm,
            String createdBy,
            Instant now) {
        String tid = requireTenant(tenantId);
        String n = requireName(name);
        InboxRecipientRealm r = Objects.requireNonNull(realm, "realm");
        String by = requireSubject(createdBy);
        Instant at = Objects.requireNonNull(now, "now");
        String description = blankToNull(descriptionOrNull);
        InboxGroup group = new InboxGroup(UUID.randomUUID().toString(), tid, n, description, r, by, at, at);
        jdbc.update(
                """
                INSERT INTO inbox_group (
                    group_id, tenant_id, name, description, realm, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                group.groupId(),
                group.tenantId(),
                group.name(),
                group.description(),
                group.realm().name(),
                group.createdBy(),
                Timestamp.from(group.createdAt()),
                Timestamp.from(group.updatedAt()));
        return group;
    }

    @Override
    public Optional<InboxGroup> get(String tenantId, String groupId) {
        String tid = requireTenant(tenantId);
        String gid = requireId(groupId, "groupId");
        List<InboxGroup> rows = jdbc.query(
                """
                SELECT group_id, tenant_id, name, description, realm, created_by, created_at, updated_at
                FROM inbox_group WHERE group_id = ? AND tenant_id = ?
                """,
                (rs, rowNum) -> mapGroup(rs),
                gid,
                tid);
        return rows.stream().findFirst();
    }

    @Override
    public List<InboxGroup> list(String tenantId, InboxRecipientRealm realm) {
        String tid = requireTenant(tenantId);
        InboxRecipientRealm r = Objects.requireNonNull(realm, "realm");
        return jdbc.query(
                """
                SELECT group_id, tenant_id, name, description, realm, created_by, created_at, updated_at
                FROM inbox_group WHERE tenant_id = ? AND realm = ?
                ORDER BY name ASC, created_at ASC
                """,
                (rs, rowNum) -> mapGroup(rs),
                tid,
                r.name());
    }

    @Override
    public boolean update(String tenantId, String groupId, String name, String descriptionOrNull, Instant now) {
        String tid = requireTenant(tenantId);
        String gid = requireId(groupId, "groupId");
        String n = requireName(name);
        Instant at = Objects.requireNonNull(now, "now");
        int updated = jdbc.update(
                """
                UPDATE inbox_group
                SET name = ?, description = ?, updated_at = ?
                WHERE group_id = ? AND tenant_id = ?
                """,
                n,
                blankToNull(descriptionOrNull),
                Timestamp.from(at),
                gid,
                tid);
        return updated > 0;
    }

    @Override
    public boolean delete(String tenantId, String groupId) {
        String tid = requireTenant(tenantId);
        String gid = requireId(groupId, "groupId");
        Boolean ok = tx.execute(status -> {
            if (get(tid, gid).isEmpty()) {
                return false;
            }
            jdbc.update("DELETE FROM inbox_group_member WHERE group_id = ? AND tenant_id = ?", gid, tid);
            jdbc.update("DELETE FROM inbox_group WHERE group_id = ? AND tenant_id = ?", gid, tid);
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public boolean addMember(
            String tenantId,
            String groupId,
            InboxRecipientRealm memberRealm,
            String memberSubjectId,
            Instant addedAt) {
        String tid = requireTenant(tenantId);
        String gid = requireId(groupId, "groupId");
        InboxRecipientRealm r = Objects.requireNonNull(memberRealm, "memberRealm");
        String subject = requireSubject(memberSubjectId);
        Instant at = Objects.requireNonNull(addedAt, "addedAt");
        if (get(tid, gid).isEmpty()) {
            return false;
        }
        Integer exists = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM inbox_group_member
                WHERE group_id = ? AND tenant_id = ? AND member_realm = ? AND member_subject_id = ?
                """,
                Integer.class,
                gid,
                tid,
                r.name(),
                subject);
        if (exists != null && exists > 0) {
            return true;
        }
        jdbc.update(
                """
                INSERT INTO inbox_group_member (
                    group_id, tenant_id, member_realm, member_subject_id, added_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                gid,
                tid,
                r.name(),
                subject,
                Timestamp.from(at));
        return true;
    }

    @Override
    public boolean removeMember(
            String tenantId, String groupId, InboxRecipientRealm memberRealm, String memberSubjectId) {
        String tid = requireTenant(tenantId);
        String gid = requireId(groupId, "groupId");
        InboxRecipientRealm r = Objects.requireNonNull(memberRealm, "memberRealm");
        String subject = requireSubject(memberSubjectId);
        int deleted = jdbc.update(
                """
                DELETE FROM inbox_group_member
                WHERE group_id = ? AND tenant_id = ? AND member_realm = ? AND member_subject_id = ?
                """,
                gid,
                tid,
                r.name(),
                subject);
        return deleted > 0;
    }

    @Override
    public List<InboxGroupMember> listMembers(String tenantId, String groupId) {
        String tid = requireTenant(tenantId);
        String gid = requireId(groupId, "groupId");
        if (get(tid, gid).isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT group_id, tenant_id, member_realm, member_subject_id, added_at
                FROM inbox_group_member
                WHERE group_id = ? AND tenant_id = ?
                ORDER BY added_at ASC, member_subject_id ASC
                """,
                (rs, rowNum) -> mapMember(rs),
                gid,
                tid);
    }

    @Override
    public List<InboxRecipient> expandMembers(String tenantId, String groupId) {
        LinkedHashMap<String, InboxRecipient> seen = new LinkedHashMap<>();
        for (InboxGroupMember member : listMembers(tenantId, groupId)) {
            InboxRecipient recipient = new InboxRecipient(member.memberRealm(), member.memberSubjectId());
            String key = recipient.realm().name() + "\0" + recipient.subjectId();
            seen.putIfAbsent(key, recipient);
        }
        return List.copyOf(seen.values());
    }

    private static InboxGroup mapGroup(ResultSet rs) throws SQLException {
        return new InboxGroup(
                rs.getString("group_id"),
                rs.getString("tenant_id"),
                rs.getString("name"),
                rs.getString("description"),
                InboxRecipientRealm.valueOf(rs.getString("realm")),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static InboxGroupMember mapMember(ResultSet rs) throws SQLException {
        return new InboxGroupMember(
                rs.getString("group_id"),
                rs.getString("tenant_id"),
                InboxRecipientRealm.valueOf(rs.getString("member_realm")),
                rs.getString("member_subject_id"),
                rs.getTimestamp("added_at").toInstant());
    }

    private static String requireTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        String tid = tenantId.trim();
        if (tid.isEmpty()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return tid;
    }

    private static String requireSubject(String subjectId) {
        Objects.requireNonNull(subjectId, "subjectId");
        String s = subjectId.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("subjectId must not be blank");
        }
        return s;
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        String n = name.trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return n;
    }

    private static String requireId(String id, String label) {
        Objects.requireNonNull(id, label);
        String v = id.trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return v;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
