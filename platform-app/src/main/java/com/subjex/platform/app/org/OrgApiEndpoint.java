package com.subjex.platform.app.org;

import com.subjex.platform.app.api.JsonApi;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OrgApiEndpoint — 组织目录只读 JSON：按租户列组织单元与成员关系。
 * <p>
 * Requires {@code org.read}. Missing/blank {@code tenantId} is 400. Optional {@code subjectId}
 * on memberships narrows via {@link JdbcOrgDirectory#listMembershipsForSubject}. No writes.
 * 需要 {@code org.read}。缺/空 {@code tenantId} 为 400。成员列表可选 {@code subjectId} 过滤。无写接口。
 */
@RestController
public class OrgApiEndpoint {

    /** JSON base for org reads — 组织只读 JSON 根路径。 */
    public static final String PATH = JsonApi.BASE + "/org";

    private final JdbcOrgDirectory directory;

    public OrgApiEndpoint(JdbcOrgDirectory directory) {
        this.directory = directory;
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
}
