# ADR 0007 — Client triad: console + uni-app H5/MP

- Status: **Accepted**
- Date: 2026-10-17 (Asia/Shanghai)
- Relates: [`docs/identity/id-progress.md`](../identity/id-progress.md) (WeChat IdP), [`ARCHITECTURE.md`](../../ARCHITECTURE.md) § Client delivery surfaces, root [`AGENTS.md`](../../AGENTS.md)

## Context / 背景

Inventory (2026-10-17): the only rich frontend in-repo is **`web/`** — a Next.js **operator console** (ops / low-code authoring). There is no H5 or WeChat mini-program project. Server-side WeChat IdP already supports mini-program `jsCode` → `code2session` (`wechat:mini`), but no client consumes it as a product UI.

盘点：仓库内唯一富前端是 **`web/` 操作员控制台**；无 H5 / 小程序工程。服务端微信 IdP 已支持小程序 `jsCode`，但无产品端消费。

User principle: maintain a foundational **Web + H5 + 微信小程序 (HBuilderX-compatible)** set, and keep subsequent development aligned.

用户原则：基础 **Web / H5 / 微信小程序（兼容 HBuilderX）**，后续开发坚持同一原则。

## Decision / 决策

### D1 — Two surfaces, not one

| Surface | Path | Stack | Audience |
| --- | --- | --- | --- |
| **Operator console** | `web/` | Next.js | Operators / authors |
| **End-user clients** | `clients/uni-app/` | **uni-app Vue3 + Vite** (HBuilderX-compatible) | End users — **H5 (= product Web)** + **微信小程序** |

- **`web/` stays** the operator console. Do **not** rewrite it in uni-app. Do **not** host C-end product pages inside `web/`.
- **`clients/uni-app/`** is the product **Web/H5 + WeChat mini-program** codebase. One uni-app project emits both targets.
- First-wave scaffold is a **shell only** (platform probe + login placeholder documenting `wechat:mini`). No full business wizards, no console clone.

- **`web/` 保持**操作员控制台；**不要**用 uni-app 重写；**不要**把 C 端塞进 `web/`。
- **`clients/uni-app/`** 承载产品 **Web/H5 + 微信小程序**（一套工程双端发行）。
- 首波仅为**壳**（探针 + 登录占位）；不做完整业务页、不克隆控制台。

### D2 — No second mobile stack without a new ADR

Do **not** introduce **Taro**, **React Native**, Flutter, or a second mini-program framework as a parallel mobile stack unless a **new ADR** supersedes this one.

未经**新 ADR**，不引入 Taro / RN / Flutter 等第二套移动栈。

### D3 — Auth boundary

- Console: existing BFF + httpOnly session (Redis optional) → platform Bearer for operators.
- Clients: **Bearer / OIDC OP** tokens for end users (exact shape TBD). **Do not** reuse the operator Redis session model.
- WeChat mini-program login protocol lives in platform IdP (`wechat:mini`); the uni-app project is the UI home for that flow.

控制台继续走现有 BFF 会话；终端客户端走 Bearer/OP；小程序协议在 IdP，UI 在 `clients/uni-app`。

## Consequences / 后果

- Contributors and agents must **classify** every new UI: console vs client (see root `AGENTS.md`).
- CI may later add `clients/uni-app` `npm run build:h5` (follow-up); mp-weixin may stay manual until credentials exist.
- Product “Web” for end users is the **uni-app H5** build in this wave — not a third React app — unless a future ADR adds `clients/web-customer`.

贡献者须区分控制台 vs 终端；CI 可后补 H5 构建；本波产品 Web = uni-app H5。

## Non-goals / 非目标（本 ADR）

- Rewriting `web/` in uni-app
- Full business pages in the first scaffold
- Replacing Java `form-render` / `page-declare` with a client-only designer
- Committing WeChat app secrets

## Links / 链接

- Scaffold: [`clients/uni-app/README.md`](../../clients/uni-app/README.md)
- IdP progress: [`docs/identity/id-progress.md`](../identity/id-progress.md)
