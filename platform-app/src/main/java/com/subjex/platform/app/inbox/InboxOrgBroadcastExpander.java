package com.subjex.platform.app.inbox;

import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.organization.Membership;
import com.subjex.platform.app.organization.OrganizationScopeResolver;
import com.subjex.platform.app.organization.TenantOrganization;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.contract.inbox.InboxRecipient;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/**
 * InboxOrgBroadcastExpander — IM-A5：按组织目标展开收件人（OrgScope ∩ ACTIVE 成员）。
 * <p>
 * Requested org ids must be ACTIVE-linked to the tenant and inside the sender's
 * {@link OrganizationScopeResolver#resolveSelfAndDescendants} scope; optional CONTAINS descendants.
 * 目标组织须租户 ACTIVE 关联且在发送者 SELF_AND_DESCENDANTS 范围内；可选展开下级。
 */
public final class InboxOrgBroadcastExpander {

    private final JdbcOrganizationStore orgStore;
    private final OrganizationScopeResolver scopeResolver;

    public InboxOrgBroadcastExpander(JdbcOrganizationStore orgStore, OrganizationScopeResolver scopeResolver) {
        this.orgStore = Objects.requireNonNull(orgStore, "orgStore");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
    }

    /**
     * Expand to OPERATOR recipients (deduped). Empty → caller should 400.
     * 展开为 OPERATOR 收件人（去重）；空列表由调用方返回 400。
     */
    public List<InboxRecipient> expand(
            String tenantId, String actorSubjectId, List<String> orgIds, boolean includeDescendants) {
        String tid = requireNonBlank(tenantId, "tenantId");
        String actor = requireNonBlank(actorSubjectId, "actorSubjectId");
        if (orgIds == null || orgIds.isEmpty()) {
            throw new IllegalArgumentException("orgIds must not be empty");
        }
        OrganizationScope scope = scopeResolver.resolveSelfAndDescendants(tid, actor);
        Set<String> tenantOrgs = tenantLinkedOrgIds(tid);
        Set<String> targetOrgs = new LinkedHashSet<>();
        for (String raw : orgIds) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("orgIds must not contain blank ids");
            }
            String orgId = raw.trim();
            if (!tenantOrgs.contains(orgId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "organization not found in tenant: " + orgId);
            }
            if (!scope.contains(orgId)) {
                throw new AccessDeniedException("organization out of OrgScope: " + orgId);
            }
            if (includeDescendants) {
                for (String child : orgStore.containsSelfAndDescendants(orgId)) {
                    if (tenantOrgs.contains(child) && scope.contains(child)) {
                        targetOrgs.add(child);
                    }
                }
            } else {
                targetOrgs.add(orgId);
            }
        }
        LinkedHashMap<String, InboxRecipient> recipients = new LinkedHashMap<>();
        for (String orgId : targetOrgs) {
            for (Membership membership : orgStore.listMembershipsForOrganization(orgId)) {
                if (!JdbcOrganizationStore.STATE_ACTIVE.equals(membership.membershipState())) {
                    continue;
                }
                String subject = membership.subjectId();
                if (subject == null || subject.isBlank()) {
                    continue;
                }
                recipients.putIfAbsent(
                        subject.trim(), new InboxRecipient(InboxRecipientRealm.OPERATOR, subject.trim()));
            }
        }
        return List.copyOf(recipients.values());
    }

    private Set<String> tenantLinkedOrgIds(String tenantId) {
        Set<String> ids = new LinkedHashSet<>();
        for (TenantOrganization link : orgStore.listTenantOrganizationsForTenant(tenantId)) {
            if (JdbcOrganizationStore.STATE_ACTIVE.equals(link.linkState())) {
                ids.add(link.organizationId());
            }
        }
        return ids;
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " required");
        }
        return value.trim();
    }
}
