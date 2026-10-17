package com.subjex.platform.app.inbox;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.contract.inbox.InboxGroup;
import com.subjex.platform.contract.inbox.InboxGroupMember;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * InboxGroupEndpoint — IM-A3 REST：站内信收件组 CRUD + 成员增删.
 * <p>
 * Base {@code /api/v1/inbox/groups/**}; requires {@code inbox.group.admin}; tenant via header/param.
 * 路径见上；需组管理权限；租户经 Header/参数。
 */
@RestController
public class InboxGroupEndpoint {

    public static final String BASE = JsonApi.BASE + "/inbox/groups";

    private final InboxGroupService groups;

    public InboxGroupEndpoint(InboxGroupService groups) {
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @GetMapping(BASE)
    public GroupsDocument list(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        List<GroupDocument> docs = groups.list(operator, resolveTenant(tenantHeader, tenantIdParam)).stream()
                .map(GroupDocument::from)
                .toList();
        return new GroupsDocument(docs);
    }

    @GetMapping(BASE + "/{groupId}")
    public GroupDocument get(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("groupId") String groupId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        return GroupDocument.from(groups.get(operator, resolveTenant(tenantHeader, tenantIdParam), groupId));
    }

    @PostMapping(BASE)
    public GroupDocument create(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody CreateGroupRequest body) {
        requireOperator(operator);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        return GroupDocument.from(
                groups.create(operator, resolveTenant(tenantHeader, tenantIdParam), body.name(), body.description()));
    }

    @PatchMapping(BASE + "/{groupId}")
    public GroupDocument update(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("groupId") String groupId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody UpdateGroupRequest body) {
        requireOperator(operator);
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        return GroupDocument.from(groups.update(
                operator, resolveTenant(tenantHeader, tenantIdParam), groupId, body.name(), body.description()));
    }

    @DeleteMapping(BASE + "/{groupId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("groupId") String groupId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        groups.delete(operator, resolveTenant(tenantHeader, tenantIdParam), groupId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(BASE + "/{groupId}/members")
    public MembersDocument listMembers(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("groupId") String groupId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        List<MemberDocument> docs = groups
                .listMembers(operator, resolveTenant(tenantHeader, tenantIdParam), groupId)
                .stream()
                .map(MemberDocument::from)
                .toList();
        return new MembersDocument(docs);
    }

    @PostMapping(BASE + "/{groupId}/members")
    public MemberDocument addMember(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("groupId") String groupId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody AddMemberRequest body) {
        requireOperator(operator);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        return MemberDocument.from(groups.addMember(
                operator, resolveTenant(tenantHeader, tenantIdParam), groupId, body.memberSubjectId()));
    }

    @DeleteMapping(BASE + "/{groupId}/members/{memberSubjectId}")
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("groupId") String groupId,
            @PathVariable("memberSubjectId") String memberSubjectId,
            @RequestHeader(value = TenantEnforcementFilter.TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        requireOperator(operator);
        groups.removeMember(operator, resolveTenant(tenantHeader, tenantIdParam), groupId, memberSubjectId);
        return ResponseEntity.noContent().build();
    }

    private static void requireOperator(OperatorPrincipal operator) {
        if (operator == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private static String resolveTenant(String header, String param) {
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        if (param != null && !param.isBlank()) {
            return param.trim();
        }
        return null;
    }

    public record CreateGroupRequest(String name, String description) {}

    public record UpdateGroupRequest(String name, String description) {}

    public record AddMemberRequest(String memberSubjectId) {}

    public record GroupsDocument(List<GroupDocument> groups) {}

    public record MembersDocument(List<MemberDocument> members) {}

    public record GroupDocument(
            String groupId,
            String tenantId,
            String name,
            String description,
            String realm,
            String createdBy,
            Instant createdAt,
            Instant updatedAt) {
        static GroupDocument from(InboxGroup group) {
            return new GroupDocument(
                    group.groupId(),
                    group.tenantId(),
                    group.name(),
                    group.description(),
                    group.realm().name(),
                    group.createdBy(),
                    group.createdAt(),
                    group.updatedAt());
        }
    }

    public record MemberDocument(
            String groupId, String tenantId, String memberRealm, String memberSubjectId, Instant addedAt) {
        static MemberDocument from(InboxGroupMember member) {
            return new MemberDocument(
                    member.groupId(),
                    member.tenantId(),
                    member.memberRealm().name(),
                    member.memberSubjectId(),
                    member.addedAt());
        }
    }
}
