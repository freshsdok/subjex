# Clients progress / 终端客户端进度

## FE-1 [done] 2026-10-17 CST — Client triad + uni-app shell

- ADR 0007: `web/` = operator console; `clients/uni-app/` = H5 + 微信小程序 (Vue3+Vite, HBuilderX).
- Docs: ARCHITECTURE §2.1, root `AGENTS.md`, README + quickstart pointers, identity cross-link.
- Scaffold: index (platform + API base + liveness), login placeholder (`wechat:mini` jsCode docs), `src/api/platform.ts` Bearer stub.
- Non-goals honored: no console clone, no business wizards, no Taro/RN.
- Local only until squash-push.

## FE-1b [done] 2026-10-17 CST — CI build:h5

- `.github/workflows/build.yml` job `clients-uni-app`: Node 22, `npm ci`, `npm run build:h5`.
- `build:mp-weixin` **not** in CI (WeChat DevTools / HBuilderX locally).

Next: end-user token / OP login wiring; real mp appid via local env (never commit secrets).
