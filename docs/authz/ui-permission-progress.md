# UI permission Manifest progress / UI 权限 Manifest 进度

- ADR: [`docs/adr/0010-ui-permission-manifest.md`](../adr/0010-ui-permission-manifest.md) (**Accepted**, 2026-10-18 Asia/Shanghai)
- Proposal: [`docs/proposals/permission-ui-config-draft.md`](../proposals/permission-ui-config-draft.md)（Accepted-ready）
- Stance: Git YAML Manifest 真源；运行时 GET；菜单置灰；按钮 disable；第一波无 OrgScope；**控制台可视化编辑绑定**（draft→promote）；不做角色/Cedar 在线编辑；FE 非安全边界

## Locked decisions / 已锁定

| # | Lock |
| --- | --- |
| 1 | Manifest = Git YAML (A) |
| 2 | Locked menu = grey visible |
| 3 | OrgScope = deferred (permission/anyOf only) |
| 4 | Missing button = disable |
| 5 | Delivery = runtime GET |
| + | Visual editor for UI bindings (console first; dual-track persist) |

## Slices / 切片

| Slice | Status | Notes |
| --- | --- | --- |
| **PC-0** | **done** | ADR 0010 + proposal + this progress；无运行时代码 |
| **PC-1** | **done** | classpath `ui-authz/console-ui.yaml` + `UiPermissionManifestCatalog`；未知权限 fail-closed；侧栏 19 项等价；单测绿 |
| **PC-2** | **done** | `GET /api/v1/ui-permissions?client=console` |
| **PC-3** | **done** | web layout / Forbidden 改读 GET |
| **PC-4** | **done** | 按钮样例绑定 |
| **PC-5** | deferred | uni-app 壳消费 GET |
| **PC-6a** | **done** | 可视化编辑器 DRAFT CRUD（permission 只选目录） |
| **PC-6b / PC-7** | **done** | Promote → live PROMOTED overlay + YAML export |
| **PC-5** | deferred | uni-app shell (user deferred) |

PC-6 可与 PC-3/4 在 PC-2 之后并行；PC-7 依赖 PC-6。

## Notes / 备注

- Cedar ADR「不上线权限编辑器」收窄为：不上线 **角色授予 / Cedar 策略** 编辑；UI **绑定** 编辑由本 ADR 明确允许。
- 编辑器权限复用 `declaration.read` / `declaration.write` / `declaration.promote`（第一波不新增权限名）。

## PC-1 landed / 已落地（2026-10-18 Asia/Shanghai）

- `platform-app/src/main/resources/ui-authz/console-ui.yaml` — 19 menu entries from `console-sections.ts`
- `com.subjex.platform.app.uiauthz.*` — schema + `UiPermissionManifestCatalog` (SnakeYAML SafeConstructor)
- `PlatformWiring.uiPermissionManifestCatalog` — startup load fail-closed
- Tests: `UiPermissionManifestCatalogTest`
- **Not in PC-1:** GET API, web layout switch, editor
- Next: **PC-2** `GET /api/v1/ui-permissions`

## PC-2 landed / 已落地（2026-10-18 Asia/Shanghai）

- `GET /api/v1/ui-permissions?client=console` — authenticated; default client=console
- Response: version/client/defaults + entries with `allowed` from principal permissions
- `UiPermissionEndpoint` + `UiPermissionAccess`; Spring matcher; `UiPermissionRejected` → 400
- Tests: `UiPermissionApiSecurityTest` (401/200/viewer allowed/unknown client) + `UiPermissionAccessTest`
- **Not in PC-2:** web FE migrate (PC-3), editor
- Next: **PC-3** console layout reads GET

## PC-3 landed / 已落地（2026-10-18 Asia/Shanghai）

- Console layout reads `GET /api/v1/ui-permissions?client=console`; uses entry `allowed` for grey/link
- Labels: `CONSOLE_MENU_LABEL_BY_ID` (Manifest id → phrases); href fallback via `console-sections`
- `console-sections.ts` kept as label/emergency fallback (not sole gate source)
- Locked menus still grey + visible + title hint; vitest `ui-permissions.test.ts` green
- **Not in PC-3:** page Forbidden migrate, button samples, editor
- Next: **PC-4** sample button bindings

## PC-4 landed / 已落地（2026-10-18 Asia/Shanghai）

- Buttons in `ui-authz/console-ui.yaml`: `btn.inbox.compose.send` (anyOf send|broadcast), `btn.org.write`
- FE: `buttonAllowed`; org page `canWrite`; compose send disabled when not allowed
- Contributor: `platform-app/src/main/resources/ui-authz/README.md`
- BE enforce unchanged
- Next: **PC-5** uni-app shell (or PC-6 editor per priority)

## PC-6a landed / 已落地（2026-10-18 Asia/Shanghai）

- Visual editor at console `/ui-authz` (declaration.read / declaration.write)
- Draft API: `GET/PUT /api/v1/ui-permissions/draft`, `GET .../draft/baseline`
- Persist: `declaration_revision` kind `ui-authz`, tenant `platform`, key `console` (YAML body)
- Live `GET /ui-permissions` was classpath-only until **PC-6b** (now PROMOTED > classpath)
- Permission selects: `OPERATOR_PERMISSION_CATALOG` only; invent → 400
- Does **not** edit role_permission
- PC-5 uni-app deferred by user priority

## PC-6b landed / 已落地（2026-10-18 Asia/Shanghai）

- Promote: `POST /api/v1/ui-permissions/draft/promote` (`declaration.promote`)
- Live `GET /ui-permissions`: **PROMOTED > classpath** (drafts excluded)
- Export: `GET .../draft/export` → effective YAML (write-back to repo by hand)
- Internal git: `platform/ui-authz/{client}.yaml` + `declaration_promote` audit
- Console `/ui-authz`: Promote + Export actions; promote gated by `declaration.promote`
- Tests: unauthorized cannot promote; after promote live `allowed` reflects new bindings
- PC-5 uni-app still deferred
