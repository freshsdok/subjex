package com.subjex.platform.app.inbox;

import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.inbox.InboxGroup;
import com.subjex.platform.contract.inbox.InboxGroupMember;
import com.subjex.platform.contract.inbox.InboxGroupPort;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/**
 * InboxGroupService — IM-A3：站内信收件组 CRUD（需 {@code inbox.group.admin}）。
 * <p>
 * Console MVP uses {@link InboxRecipientRealm#OPERATOR} groups only; tenant fail-closed.
 * 控制台首波只做 OPERATOR 组；租户失败关闭。
 */
public final class InboxGroupService {

    private final InboxGroupPort groups;
    private final OperatorTenantAccess tenantAccess;
    private final TenantGuard tenantGuard;
    private final Clock clock;

    public InboxGroupService(
            InboxGroupPort groups,
            OperatorTenantAccess tenantAccess,
            TenantGuard tenantGuard,
            Clock clock) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** List OPERATOR mailing groups in tenant — 列本租户操作员收件组. */
    public List<InboxGroup> list(OperatorPrincipal operator, String tenantId) {
        String tid = requireAdmin(operator, tenantId);
        return groups.list(tid, InboxRecipientRealm.OPERATOR);
    }

    /** Get one group or 404 — 取一组，不存在 404. */
    public InboxGroup get(OperatorPrincipal operator, String tenantId, String groupId) {
        String tid = requireAdmin(operator, tenantId);
        return groups.get(tid, groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "inbox group not found"));
    }

    /** Create OPERATOR group — 创建操作员收件组. */
    public InboxGroup create(OperatorPrincipal operator, String tenantId, String name, String description) {
        String tid = requireAdmin(operator, tenantId);
        return groups.create(
                tid, name, description, InboxRecipientRealm.OPERATOR, operator.subjectId(), clock.instant());
    }

    /** Update name/description — 更新名称/说明. */
    public InboxGroup update(
            OperatorPrincipal operator, String tenantId, String groupId, String name, String description) {
        String tid = requireAdmin(operator, tenantId);
        requireGroup(tid, groupId);
        if (!groups.update(tid, groupId, name, description, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "inbox group not found");
        }
        return requireGroup(tid, groupId);
    }

    /** Delete group + members — 删除组及成员. */
    public void delete(OperatorPrincipal operator, String tenantId, String groupId) {
        String tid = requireAdmin(operator, tenantId);
        if (!groups.delete(tid, groupId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "inbox group not found");
        }
    }

    /** List members — 列成员. */
    public List<InboxGroupMember> listMembers(OperatorPrincipal operator, String tenantId, String groupId) {
        String tid = requireAdmin(operator, tenantId);
        requireGroup(tid, groupId);
        return groups.listMembers(tid, groupId);
    }

    /**
     * Add member (idempotent). Member realm must match group realm (OPERATOR MVP).
     * 加成员（幂等）；成员领域须与组一致（首波 OPERATOR）。
     */
    public InboxGroupMember addMember(
            OperatorPrincipal operator, String tenantId, String groupId, String memberSubjectId) {
        String tid = requireAdmin(operator, tenantId);
        InboxGroup group = requireGroup(tid, groupId);
        if (memberSubjectId == null || memberSubjectId.isBlank()) {
            throw new IllegalArgumentException("memberSubjectId required");
        }
        String subject = memberSubjectId.trim();
        if (!groups.addMember(tid, groupId, group.realm(), subject, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "inbox group not found");
        }
        return groups.listMembers(tid, groupId).stream()
                .filter(m -> m.memberSubjectId().equals(subject) && m.memberRealm() == group.realm())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("member missing after add"));
    }

    /** Remove member — 移除成员. */
    public void removeMember(
            OperatorPrincipal operator, String tenantId, String groupId, String memberSubjectId) {
        String tid = requireAdmin(operator, tenantId);
        InboxGroup group = requireGroup(tid, groupId);
        if (memberSubjectId == null || memberSubjectId.isBlank()) {
            throw new IllegalArgumentException("memberSubjectId required");
        }
        if (!groups.removeMember(tid, groupId, group.realm(), memberSubjectId.trim())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "inbox group member not found");
        }
    }

    private InboxGroup requireGroup(String tenantId, String groupId) {
        return groups.get(tenantId, groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "inbox group not found"));
    }

    private String requireAdmin(OperatorPrincipal operator, String tenantId) {
        Objects.requireNonNull(operator, "operator");
        tenantGuard.requireTenant(tenantId);
        String tid = tenantId.trim();
        tenantAccess.requireGranted(operator, tid);
        if (!operator.permissionNames().contains(OperatorPermission.INBOX_GROUP_ADMIN.permissionName())) {
            throw new AccessDeniedException("missing permission " + OperatorPermission.INBOX_GROUP_ADMIN.permissionName());
        }
        return tid;
    }
}
