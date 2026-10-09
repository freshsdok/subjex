# ADR 0001 — O1 Model Freeze: Subject / Organization / Tenant ontology

- Status: **Accepted (model freeze)** — O2–O5 landed (API/Console); O6 zero-code next
- Date: 2026-10-09 (Asia/Shanghai)
- Scope: O1 docs freeze; O2 adds `organization` / `membership` / `organization_relation` / `tenant_organization` (legacy `org_unit` retained)

## Context / 背景

Flyway V13 introduced a **tenant-scoped thin org model**:

- `org_unit` — department/team tree keyed by `(tenant_id, org_unit_id)`
- `org_membership` — subject ↔ unit **inside a tenant**

That model shipped Thin-org-1/2 and Org-W1/W2. It conflates three concerns:

1. **World organization** (real-world / business org graph)
2. **Digital tenant space** (isolation, grants, runtime boundary)
3. **Authorization scope** (what a subject may touch in a context)

Owner V1.0 ontology revision freezes six concepts and pauses structural expansion of `org_unit` / `org_membership` until this freeze passes review. Bugfixes on the legacy path remain allowed.

V13 引入的租户内薄组织把「现实组织」「数字租户」「授权范围」叠在同一套表上。业主 V1.0 将六个概念冻结；O1 通过内审前，暂停对旧表增加结构性能力（可修 bug）。

## Decision / 决策

Replace the long-term foundation with **six frozen concepts**:

| Concept | Role |
| --- | --- |
| **Subject** | Actor identity in the platform (independent of Tenant and Organization) |
| **Organization** | Real-world / business organization (not a tenant department alias) |
| **Tenant** | Digital isolation / governance space |
| **Membership** | Subject belongs to Organization — **no tenant**, **no permission** |
| **OrganizationRelation** | Organization → Organization edges (e.g. CONTAINS); hierarchy lives here |
| **TenantOrganization** | Many-to-many link Tenant ↔ Organization — **membership ≠ authorization** |

### Four layers (strict separation) / 四层职责严格分离

```text
World Model          Subject · Organization · Tenant
        ↓
Relationship         Membership · OrganizationRelation · TenantOrganization
        ↓
Context              current Tenant + derived org scope + grants
        ↓
Authorization        named permission + OrgScope — fail-closed
```

- **Organization** describes the social / business world.
- **Tenant** describes the digital runtime / governance space.
- **Membership** only answers: which Organization does this Subject belong to?
- **Authorization** answers: in this Context, may this Subject perform this Action on this Resource?

Do **not** invent `organizationUnitRef` / `tenantOrgUnitRef` unless a future real product proves distinct objects are required.

Zero-code pickers converge to **SubjectPicker** + **OrganizationPicker** (Context narrows candidates). Field kinds (O6 done): `subjectRef` / `organizationRef` (legacy `userRef` / `orgRef` dual-accept).

### Explicit non-goals this round / 本轮明确不做

HR system · Position/Job · dual/part-time · appointment history · legal governance · SCIM · Zanzibar · full Cedar/Casbin engine · matrix orgs · graph DB · Organization auto-merge · cross-org identity sync · full ABAC DSL.

### Gate / 门禁

**O1 complete** (review PASS). **O2** Flyway `V22__organization_ontology.sql` + `JdbcOrganizationStore`. Legacy `org_unit` / `org_membership` remain the *compat* runtime tables until O7; new world-model tables run alongside.

## Consequences / 后果

**Positive**

- Clear ontology; multi-tenant org participation without merging orgs across tenants
- AuthZ can require explicit OrgScope modes (`NONE` ≠ `UNRESTRICTED`)
- Migration mapping is 1:1 per tenant row; **forbid auto cross-tenant org merge**

**Negative / cost**

- Dual-read period (O3) before legacy removal
- OrgScope / AccessChecker / SqlRbacPolicyEngine refactor (O4)
- Declaration field kinds must migrate (O6); console `/org` migrated in O5

**Deferred slices:** O2 Persistence → O3 Compatibility & Backfill → O4 Authorization → O5 API/Console → O6 Zero-code → O7 Legacy Removal (only after all PASS gates).

## Links / 链接

- Ontology pack: [`docs/ontology/README.md`](../ontology/README.md)
- Review Q&A: [`docs/ontology/review-questions.md`](../ontology/review-questions.md)
- Migration map: [`docs/ontology/migration-mapping.md`](../ontology/migration-mapping.md)
- Machine gates: [`docs/ontology/machine-gates.md`](../ontology/machine-gates.md)
- Roadmap: [`docs/lowcode-roadmap.md`](../lowcode-roadmap.md)
- Legacy SQL: `platform-app/.../V13__org_unit.sql`
