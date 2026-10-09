package com.subjex.platform.app.org;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * OrgApiEndpoint — 组织目录 JSON：按租户列组织单元与成员关系，并支持写操作。
 * <p>
 * GET needs {@code org.read}. PUT/DELETE need {@code org.write} plus an operator–tenant grant.
 * Missing/blank {@code tenantId} is 400. Optional {@code subjectId} on GET memberships narrows via
 * {@link JdbcOrgDirectory#listMembershipsForSubject}.
 * GET 要 {@code org.read}。PUT/DELETE 要 {@code org.write} 与操作员—租户授权。
 * 缺/空 {@code tenantId} 为 400。成员 GET 可选 {@code subjectId} 过滤。
 */
@RestController
public class OrgApiEndpoint {

    /** JSON base for org APIs — 组织 JSON 根路径。 */
    public static final String PATH = JsonApi.BASE + "/org";

    private final JdbcOrgDirectory directory;
    private final OperatorTenantAccess tenantAccess;
    private final OperatorActionAudit audit;

    public OrgApiEndpoint(
            JdbcOrgDirectory directory, OperatorTenantAccess tenantAccess, OperatorActionAudit audit) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @GetMapping(PATH + "/units")
    public UnitsDocument units(@RequestParam(value = "tenantId", required = false) String tenantId) {
        List<OrgUnitDocument> units =
                directory.listUnits(tenantId).stream().map(OrgApiEndpoint::unit).toList();
        return new UnitsDocument(units);
    }

    @GetMapping(PATH + "/memberships")
    public MembershipsDocument memberships(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "subjectId", required = false) String subjectId) {
        List<OrgMembership> rows = subjectId == null || subjectId.isBlank()
                ? directory.listMemberships(tenantId)
                : directory.listMembershipsForSubject(tenantId, subjectId);
        List<MembershipDocument> memberships =
                rows.stream().map(OrgApiEndpoint::membership).toList();
        return new MembershipsDocument(memberships);
    }

    @PutMapping(PATH + "/units/{orgUnitId}")
    public OrgUnitDocument upsertUnit(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("orgUnitId") String orgUnitId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody UnitWriteRequest body) {
        String tid = requireTenant(operator, tenantId);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        OrgUnit saved = directory.upsertUnit(
                tid, orgUnitId, body.parentOrgUnitId(), body.unitName(), body.unitState());
        audit.record(operator, "org.unit.upsert", tid + "/" + saved.orgUnitId(), AuditOutcome.ALLOWED);
        return unit(saved);
    }

    @PutMapping(PATH + "/memberships")
    public MembershipDocument upsertMembership(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody MembershipWriteRequest body) {
        String tid = requireTenant(operator, tenantId);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        OrgMembership saved =
                directory.upsertMembership(tid, body.subjectId(), body.orgUnitId(), body.membershipState());
        audit.record(
                operator,
                "org.membership.upsert",
                tid + "/" + saved.subjectId() + "/" + saved.orgUnitId(),
                AuditOutcome.ALLOWED);
        return membership(saved);
    }

    @DeleteMapping(PATH + "/memberships")
    public void removeMembership(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "subjectId", required = false) String subjectId,
            @RequestParam(value = "orgUnitId", required = false) String orgUnitId) {
        String tid = requireTenant(operator, tenantId);
        if (!directory.removeMembership(tid, subjectId, orgUnitId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        audit.record(
                operator,
                "org.membership.remove",
                tid + "/" + subjectId.trim() + "/" + orgUnitId.trim(),
                AuditOutcome.ALLOWED);
    }

    private String requireTenant(OperatorPrincipal operator, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required");
        }
        String tid = tenantId.trim();
        tenantAccess.requireGranted(operator, tid);
        return tid;
    }

    private static OrgUnitDocument unit(OrgUnit row) {
        return new OrgUnitDocument(
                row.tenantId(), row.orgUnitId(), row.parentOrgUnitId(), row.unitName(), row.unitState());
    }

    private static MembershipDocument membership(OrgMembership row) {
        return new MembershipDocument(
                row.tenantId(), row.subjectId(), row.orgUnitId(), row.membershipState());
    }

    /** UnitsDocument — 某租户的组织单元列表。 */
    public record UnitsDocument(List<OrgUnitDocument> units) {}

    /** OrgUnitDocument — 一个组织单元。 */
    public record OrgUnitDocument(
            String tenantId, String orgUnitId, String parentOrgUnitId, String unitName, String unitState) {}

    /** MembershipsDocument — 成员关系列表。 */
    public record MembershipsDocument(List<MembershipDocument> memberships) {}

    /** MembershipDocument — 一条主体-组织单元成员关系。 */
    public record MembershipDocument(
            String tenantId, String subjectId, String orgUnitId, String membershipState) {}

    /** Unit write body — 组织单元写入正文。 */
    public record UnitWriteRequest(String parentOrgUnitId, String unitName, String unitState) {}

    /** Membership write body — 成员关系写入正文。 */
    public record MembershipWriteRequest(String subjectId, String orgUnitId, String membershipState) {}
}
