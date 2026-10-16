# Org Builder — Later inventory (note only) / Later 清单（仅盘点）

Date: 2026-10-16. After Must + Should S1–S6 on `github/main` (`f5c621b`).

| ID | Capability | Blocker / dependency | Status |
| --- | --- | --- | --- |
| L1 | Matrix / dotted-line reporting | `relation_kind = MATRIX_OF`; canvas dashed; ADR 0006 D3 | **Sketch landed** (API + OrgScope-gated CRUD; canvas dashed overlay + side panel; not in OrgScope closure) |
| L2 | Position / vacancy | New entity; ADR 0001 non-goal historically | Open |
| L3 | Parallel dimension trees (cost center vs legal) | `organization_kind` or namespaced graphs | Open |
| L4 | Org Studio style draft reorg | Draft design object + approval + mass apply (thin workflow) | Open |
| L5 | Effective dating (as-of org chart) | Temporal edges | Open |
| L6 | Directory visibility (hide dept / cross-dept limits) | Product policy; not AuthZ OrgScope | Open |

Pointer: [`org-builder-target.md`](./org-builder-target.md) Later table; ADR [`0006`](../adr/0006-org-builder-and-relation-kinds.md).

## L1 sketch (landed) / L1 草图（已落地）

- Kind: `RelationKind.MATRIX_OF` (from = matrix supervisor org → to = supervised). CONTAINS unchanged.
- Store: `listActiveMatrixRelations` / `upsertMatrixOf` / `endMatrixOf`. No CONTAINS cycle rule; self-edge rejected; multi-parent OK.
- API: `GET|PUT|DELETE /api/v1/organizations/matrix-relations` — both ends tenant-linked + in actor OrgScope; never expands OrgScope.
- Console: canvas dashed overlay (`strokeDasharray`); side-panel list/add/remove. Tree CONTAINS drag untouched.
- Tests: store ORG-L1, scope “MATRIX does not expand”, API security CRUD + out-of-scope.

Next after L1: search read path MVP (see identity progress), then L2 Position only when product opens the gate.
