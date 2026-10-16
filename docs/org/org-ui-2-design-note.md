# ORG-UI-2 design note — drag reparent + move API

- Status: **Implemented** (ORG-UI-2)
- Date: 2026-10-16 (Asia/Shanghai)
- Depends: [`org-builder-target.md`](org-builder-target.md), ADR [`0006`](../adr/0006-org-builder-and-relation-kinds.md)

## Goal

Allow operators to **drag a node onto a new parent** in the indented tree (or drop on “root”), with **preview → confirm**, server-side **cycle reject**, **OrgScope** gate, and **audit**.

## Backend

```http
POST /api/v1/organizations/{organizationId}/move?tenantId=
Content-Type: application/json
{ "newParentOrganizationId": "<id>" | null }

POST /api/v1/organizations/{organizationId}/move/preview?tenantId=
→ {
  "organizationId", "fromParentOrganizationId", "toParentOrganizationId",
  "subtreeOrganizationCount", "directMembershipCount",
  "wouldCycle": false, "inScope": true
}
```

Path uses `move/preview` (not `move:preview`) so Next.js platform proxy encoding stays simple.


Semantics (ADR 0006 D2):

1. Resolve ACTIVE CONTAINS edge into `organizationId` (at most one parent for MVP tree UX).
2. If cycle or self-parent → 400.
3. Else ENDED old edge (if any) + ACTIVE new CONTAINS (if parent non-null) in one transaction.
4. Require `org.write` + tenant grant; OrganizationScope must cover moved node (and preferably new parent).
5. Audit `org.organization.move` with from/to parent ids.

No new `relation_kind`; no `sort_order` in UI-2 (siblings stay name-sorted).

## Frontend

- Enable DnD on `OrgTreePanel` (e.g. HTML5 DnD or `@dnd-kit` if we add the dep — prefer HTML5 first to avoid new package unless needed).
- Drop target: another node (becomes parent) or explicit root zone.
- On drop: call `:preview` → show review strip (counts) → Confirm → `move` → reload lists.
- Disable drag when `!canWrite`; show denied if preview `inScope: false`.
- Keep Tree/Table tabs; DnD only on Tree.

## Tests

- MockMvc: cycle 400, happy move, 403 out of scope, audit fired.
- Vitest: drop → preview payload shape helpers; refuse drop onto descendant (client mirror of cycle).

## Out of scope (UI-3+)

Multi-select move, merge/split, canvas drag, sibling order, matrix edges.
