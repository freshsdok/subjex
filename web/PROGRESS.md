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
6d. [next] Push local commits since 8abf74d via Mac gh (scaffold → this slice).
5. [todo] Screens: config, audit, deploy, forms, codegen; i18n zh/en; three skins.

Dev runtime — 开发运行环境: `next dev -p 3000` pid in /tmp/nextdev.pid, log /tmp/nextdev.log; docker container `subjex-web-dev-pg` (postgres:16-alpine, 127.0.0.1:15432, subjex/subjex/subjex); app log /tmp/subjex-dev/app.log, pid in /tmp/subjex-dev/app.pid. Rebuild jar first: `mvn -o -q package -DskipTests -pl platform-app -am`.
