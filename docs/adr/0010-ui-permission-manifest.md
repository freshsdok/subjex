# ADR 0010 — UI permission Manifest + console visual editor / UI 权限 Manifest 与控制台可视化编辑器

- Status: **Accepted**（2026-10-18 Asia/Shanghai；用户「请继续」锁定五项默认 + 要求可视化编辑器；PC-0 收工，PC-1 可开工）
- Date: 2026-10-18 (Asia/Shanghai)
- Relates: [`docs/operator-permissions.md`](../operator-permissions.md)（AX-1/2/3）、[`docs/authz/cedar-or-casbin-adr.md`](../authz/cedar-or-casbin-adr.md)、[`docs/ontology/o4-authorization.md`](../ontology/o4-authorization.md)、ADR 0001（Organization / AUTH-01）、ADR 0007（client triad）、声明双轨 Z3–Z5、[`docs/proposals/permission-ui-config-draft.md`](../proposals/permission-ui-config-draft.md)、[`docs/authz/ui-permission-progress.md`](../authz/ui-permission-progress.md)

## Context / 背景

Inventory (2026-10-18 tip ~`72b0094`):

| Piece | Location | Gap |
| --- | --- | --- |
| Named permissions | `OperatorPermission` + `platform_permission` | Catalog exists; UI does not bind via config |
| Explainable AuthZ | `PolicyEngine` (Cedar default) + `AccessChecker` + `OrganizationScope` | API/context ready; console nav hardcoded |
| Console nav | `web/.../console-sections.ts` + `layout.tsx` | Grey locked sections; **code-only** catalog |
| Pages / buttons | Per-page `me.permissions.has` | Scattered; hard to audit / multi-client reuse |
| Declaration dual-track | `declaration_revision` → promote → internal git YAML | entity/form/flow only — **reusable pattern** |
| Capability YAML | `capabilities/*.yaml` | Same classpath-YAML family; not UI bindings |
| Cedar ADR non-goal | “No online permission/role editor” | Meant **no role / Cedar policy editor** — user now wants **UI binding** editor only |

盘点：AuthZ 上下文已齐；控制台菜单/按钮门禁仍硬编码；声明双轨可复用；用户要配置化 + **可视化编辑绑定**。

Product ask: developers control **pages / menus / buttons** via simple config on the existing context system; plus a **console visual editor** for those bindings.

产品要求：基于现有上下文用配置控页面/菜单/按钮；并要控制台可视化编辑这些绑定。

## Decision / 决策

### D1 — Git YAML Manifest is source of truth

UI bindings live in a versioned **Manifest YAML** (classpath baseline under e.g. `platform-app/src/main/resources/ui-authz/`, promoted overlays via dual-track). Schema maps:

| `kind` | Keys | Gate |
| --- | --- | --- |
| `menu` | `id`, `href`, `permission` **or** `anyOf[]` | Grey when denied (locked UX) |
| `page` | `id`, `route`, same gate | ForbiddenNotice when denied |
| `button` | `id`, same gate | **Disable** when denied |

- All permission strings ⊆ `OperatorPermission` catalog (RT-3 pick-only; invent → fail-closed).
- **No `orgScopeMode` in Manifest for wave 1** (OrgScope stays API-only; O4 modes unchanged).
- Manifest does **not** grant roles; it only binds UI → named permission.

### D2 — Runtime delivery via GET

`GET /api/v1/ui-permissions?client=console` (authn required) returns the **effective** Manifest (PROMOTED > classpath; draft preview optional for editors). Frontends intersect with `me.permissions` for UX. **Build-time codegen is optional later, not the primary path.**

### D3 — Locked UX defaults

| Concern | Lock |
| --- | --- |
| Locked menu | **Grey + visible** (tooltip names missing permission); not hide |
| Missing button | **Disable** |
| OrgScope in Manifest | **Deferred** (wave-1 = permission/anyOf only) |
| Security boundary | **Backend only**; FE hide/disable is UX |

### D4 — Visual editor for UI bindings (wave-1 console)

Override Cedar ADR’s broad “no online editor” **only** for **UI binding** authoring:

| Allowed | Forbidden |
| --- | --- |
| Edit menu/page/button → permission/anyOf | Edit `role_permission` / who holds what |
| `<select>` from `OperatorPermission` | Free-text invent permissions |
| Console client first | uni-app editor in wave 1 |
| Draft → promote → YAML | Online Cedar policy / AuthZ DSL |

**Persist:** reuse **declaration dual-track** semantics:

1. New declaration kind (or equivalent store) **`ui-authz`**, key per client (`console`).
2. Save DRAFT with `declaration.write`; promote with `declaration.promote` (existing second-operator rules if already required for promote).
3. Promoted body = Manifest YAML into internal git / effective overlay; classpath baseline remains clone-reproducible.
4. Audit save/promote actions.
5. Runtime `GET` reads effective Manifest; editor may load DRAFT for preview.

Editor gate: list/open `declaration.read`; no new permission names in wave 1.

### D5 — Multi-frontend (ADR 0007)

- `web/`: Manifest consumer + **visual editor**.
- `clients/uni-app/`: same schema later (PC-5); no editor in first wave.

## Non-goals / 明确不做

- Self-authored AuthZ DSL; online Cedar policy editor; online role grant UI.
- OrgScope fields on Manifest in PC-1…PC-7 wave.
- Default hide for locked menus; default hide for buttons.
- Treating FE as authorization.
- Rewriting `web/` in uni-app; cloning full console into H5/MP.

## Consequences / 后果

- Contributors add console sections via Manifest (editor or YAML) + i18n phrases, not new hard-coded gate tables.
- Cedar ADR remains: **no role/policy DSL editor**; this ADR carves **UI binding** editor only.
- PC slices: schema → GET → web migrate → buttons → uni-app shell → **editor** → **promote**; see progress doc.
- Residual: many pages still call `AccessChecker` directly — out of scope except where button/page ids are rebound.

## Slice plan / 切片（PC）

| Slice | Deliverable |
| --- | --- |
| **PC-0** (this) | ADR Accepted + proposal lock + progress; **no runtime code** |
| **PC-1** | **done** Schema + classpath sample + validate |
| **PC-2** | **done** `GET /api/v1/ui-permissions` |
| **PC-3** | **done** Console layout reads GET |
| **PC-4** | **done** Sample button bindings |
| **PC-5** | **done** uni-app shell consumes GET |
| **PC-6a** | **done** Visual editor DRAFT CRUD |
| **PC-6b / PC-7** | **done** Promote `ui-authz` + export YAML + effective read |
| **PC-5** | **done** | uni-app shell |

**Next:** owner order #3 terminal auth → IM-6.

## References

- Proposal: `docs/proposals/permission-ui-config-draft.md`
- Progress: `docs/authz/ui-permission-progress.md`, `web/PROGRESS.md`
- Nav today: `web/src/app/(console)/console-sections.ts`
- Dual-track: `DeclarationPromoteService`, `InternalDeclarationGit`, `DeclarationKind`
