# web/ progress — 前端进度笔记

Slice log — 切片记录（每片做完即本地提交）:

1. [done] Scaffold: create-next-app 16.3.8 (TS, Tailwind 4, ESLint, App Router, src/). Node 20.19 is enough (next needs >=20.9).
   脚手架：已建好，Node 20.19 够用。
2. [done] Dev deps: vitest@4.1.11 (vitest 5 needs Node 22) + openapi-typescript; scripts test/typecheck/gen:api. Debian npm 9.2 crashed (edgesOut bug) -> use npm 10 at ~/.local/bin (on PATH via ~/.bashrc).
   开发依赖已装；系统自带 npm 9.2 有 bug，改用 ~/.local/bin 下的 npm 10。
3. [done] `openapi.json` (snapshot of /api/v1/openapi.json, 10 paths) -> `src/api/schema.d.ts` via `npm run gen:api`. The earlier 403 was a stale 12:38 jar; rebuilt jar returns 200.
   接口类型已生成；之前的 403 是旧 jar 导致，重建后正常。
4a. [done] `src/server/operator-session.ts` (in-memory session map, random id in httpOnly SameSite=Strict cookie `subjex_session`, 8h), `/api/session` POST sign-in (verifies via /api/v1/me) + DELETE sign-out, `/api/platform/[...segments]` GET/PUT proxy to /api/v1. Smoke-tested with curl: 401 without session, 401 wrong password, 200 after login, 401 after logout.
    代理与会话已完成并手测通过。
4b. [done] `/login` (client SignInForm, reasons shown inline), `(console)` route group layout (header with operator + sign-out, side nav) and overview page listing permissions; `src/i18n/phrases.ts` zh/en (cookie `subjex_language`, default zh); `src/server/platform-reader.ts` server reads with redirect on 401. Dropped Google fonts (system font stack). Smoke: / -> 307 /login signed out; signed in shows permissions.
    登录页与控制台外框完成。
5a. [done] Nav from `(console)/console-sections.ts` (locked sections greyed with 'needs permission X' tooltip, not hidden); `components/page-state.tsx` (PageHeading, ForbiddenNotice on 403, LoadFailedNotice); `/services` and `/audit` tables; `fillPhrase` placeholders.
    服务页、审计页、导航权限置灰已完成。
5b. [done] `/config` table (key, effective value, origin word); `config-override-cell.tsx` edit -> review summary (before/after, audited) -> confirm save via PUT proxy; read-only notice without config.write. Smoke: PUT returned origin=override and audit entry recorded.
    配置页与两步确认修改已完成。
5c. [done] `/deploy` (applied/not-applied notice first, then workloads), `/forms` (disabled preview fieldset), `/codegen` (record source preview + field/type table).
    部署、表单、代码生成三页完成。
5d. [done] `components/appearance-switcher.tsx` in header (language select + skin select, cookies `subjex_language`/`subjex_skin`, 1 year); `server/skin.ts` maps /api/v1/skins `--page-*` variables onto console CSS variables on the console wrapper. Login page uses default skin.
    语言与外观切换完成。
6a. [done] Vitest 8 tests in `tests/` (phrases, operator-session expiry, skin mapping via exported `skinStyleFor`); `next build` passes (11 routes, all dynamic).
    单元测试 8 个通过，生产构建通过。
6b. [done] Playwright Chromium screenshots in `docs/screenshots/` (login, wrong password, overview, services, config review, audit, en+calm). Install lives under /workspace/web-shots (not in repo).
    截图已落盘。
6c. [done] `docs/design-notes.md` (Nacos/Consul/Spring Boot Admin/Keycloak real issue URLs + 待讨论 6 点), web README, root README console section.
6d. [done] Pushed as remote `2d9577f` (tree of all web/ slices since 8abf74d). Local commits remain as history; remote is one squash-style Git Data commit.
    已推送到 GitHub。
5. [todo] Screens: config, audit, deploy, forms, codegen; i18n zh/en; three skins.

Dev runtime — 开发运行环境: `next dev -p 3000` pid in /tmp/nextdev.pid, log /tmp/nextdev.log; docker container `subjex-web-dev-pg` (postgres:16-alpine, 127.0.0.1:15432, subjex/subjex/subjex); app log /tmp/subjex-dev/app.log, pid in /tmp/subjex-dev/app.pid. Rebuild jar first: `mvn -o -q package -DskipTests -pl platform-app -am`.

Round 2 — 第二轮（2026-10-05 决策后）:

7a. [done] Decisions + tech debt TD-1 (in-memory session) recorded in docs/design-notes.md; login page language select.
    决策与技术债已记录；登录页可选语言。
7b. [done] Overview sections: service health, recent audit (latest 5), unapplied deploy notice; each section degrades to a permission hint on 403.
    概览分区已上线。
7c. [done] Real form submit: POST /api/v1/forms/{formKey}/submissions validates fields, registers service (registry.write + audit), UI edit → review → confirm; proxy POST enabled.
    表单真提交已打通。

7d. [done] Pushed as remote `96c66e5` (decisions, overview sections, form submit).
    已推送到 GitHub。

Node 5 / TD-1 — 节点五会话:

8a. [done] Operator sessions use Redis when SESSION_REDIS_URL or REDIS_URL is set; otherwise in-memory (tests + single-node). Call sites are async.
    配置了 Redis 地址则会话进 Redis；否则仍用内存。调用方已改为 async。

Auth roadmap (design only) — 认证路线图（仅设计，2026-10-05）:

9a. [done] Inventory + plan in `docs/auth-roadmap.md` (no token/SSO/MFA/UI code this slice).
    盘点现状并写入路线图；本片不实现令牌 / SSO / MFA / 界面。
    Next slices (see roadmap): A operator console create/password/grants → B tenants → C Bearer tokens (drop Basic-at-rest) → D TOTP → E OIDC.
    下一片：A 操作员控制台补齐 → B 租户管理 → C 平台令牌 → D TOTP → E OIDC。

Auth roadmap Slice A — 操作员控制台补齐（2026-10-05）:

9b. [done] Console operator admin UI against existing `/api/v1/operators/**`: create (login/password/role, review→confirm), admin password reset, tenant grants editor (add/remove including `*`, review→confirm). Fail-closed notice for empty grants. zh/en phrases. Minimal vitest for grant helpers.
    控制台操作员管理：新建、管理员重置口令、租户授权编辑（含通配 *）；无授权失败关闭提示；中英文案；授权辅助单测。
    Next: Slice B tenants API + `/tenants` UI. Do not start C–E until A/B land as planned.
    下一片：B 租户 API 与界面。按计划先不要开 C–E。

Auth roadmap Slice B — 租户管理（2026-10-05）:

9c. [done] `tenant.manage` permission (V7); `/api/v1/tenants` list/get/create/rename/disable/enable; soft-disable = SUSPENDED; reserved `platform` immutable; task submit refuses SUSPENDED; console `/tenants` with review→confirm; nav; zh/en; vitest helpers.
    权限、REST、软禁用、保留租户不可改、禁用租户拒绝任务、控制台页与导航、中英文案、辅助单测。
    Next: Slice C Bearer tokens (drop Basic-at-rest). Do not start D–E until C lands.
    下一片：C 平台令牌。按计划先不要开 D–E。

Auth roadmap Slice C — 平台令牌（2026-10-05）:

9d. [done] Opaque Bearer access + rotating refresh; console drops Basic-at-rest entirely. Login via `/api/v1/auth/login`; session stores encrypted access/refresh; proxy Bearer + silent refresh; logout revokes family. Vitest session tests updated.
    不透明 Bearer + 轮换刷新；控制台不再静态保留 Basic。登录走令牌端点；会话只存加密令牌；代理 Bearer 与静默刷新；退出吊销族。
    Next: Slice D TOTP MFA. Do not start E until D lands as planned.
    下一片：D TOTP。按计划先不要开 E。
