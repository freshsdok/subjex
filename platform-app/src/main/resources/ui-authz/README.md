# ui-authz — UI permission Manifest (ADR 0010)

Classpath baseline for console (and later uni-app) UI bindings.

## Resolution order / 解析顺序（PC-6b）

Live `GET /api/v1/ui-permissions`:

1. Latest **PROMOTED** revision in `declaration_revision` (`kind=ui-authz`, `tenant=platform`, `key={client}`)
2. Else **classpath** `ui-authz/{client}-ui.yaml` (e.g. `console-ui.yaml`)

Unpromoted **DRAFT** rows never affect live GET (editor-only).

线上 GET：已晋升 → 否则 classpath。未晋升草稿不影响线上。

## Contributor note / 贡献者说明

1. **Gate source:** edit via console `/ui-authz` (draft → promote) or edit `console-ui.yaml` then redeploy. Do **not** invent permission names — only `OperatorPermission` catalog values.
2. **Menus:** `kind: menu` + `href` + `permission` or `anyOf`. Console labels: add id → phrase in `web/src/lib/ui-permissions.ts` (`CONSOLE_MENU_LABEL_BY_ID`).
3. **Buttons (PC-4):** `kind: button` + `permission` or `anyOf`. Wire FE with `buttonAllowed(manifest, id, fallback)` and **disable** when false (`missingButtonEffect: disable`). Backend APIs still enforce.
4. **Sample button ids:** `btn.inbox.compose.send`, `btn.org.write`.
5. Unknown permission / unknown YAML keys → **fail-closed** at parse / promote / startup.

门禁改 YAML；权限只选目录；按钮用 `buttonAllowed` 禁用；后端仍强制校验。

## Editor + promote (PC-6a / PC-6b)

- Console: `/ui-authz` — list/edit entries; permission `<select>` from catalog only.
- Draft API: `GET/PUT /api/v1/ui-permissions/draft?client=console` (`declaration.read` / `declaration.write`).
- Promote: `POST /api/v1/ui-permissions/draft/promote?client=console` (`declaration.promote`) — writes internal git `platform/ui-authz/{client}.yaml`, marks `PROMOTED`, audit row. Single operator (platform UI bindings; not dual-op tenant entity promote).
- Export / write-back: `GET /api/v1/ui-permissions/draft/export?client=console` returns effective YAML (`PROMOTED` else classpath). Download and commit into `ui-authz/{client}-ui.yaml` when you want the jar baseline updated; runtime does **not** rewrite classpath files.
- Storage: `declaration_revision` (`ui-authz` / tenant `platform` / key `console`).
- Does **not** edit `role_permission`.
