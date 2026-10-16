# Agent / contributor rules for subjex

## Client delivery surfaces（硬纪律）

Before adding or changing UI, **classify** the audience:

| Kind | Location | Stack |
| --- | --- | --- |
| **Operator console** (ops, low-code authoring, audit, tenants, org builder, declarations) | `web/` | Next.js |
| **End-user product UI** (H5 / 微信小程序 / product Web) | `clients/uni-app/` | uni-app Vue3 + Vite (HBuilderX-compatible) |

Rules:

1. **Do not** put C-end / end-user product pages into `web/`.
2. **H5 and WeChat mini-program** work **only** under `clients/uni-app/` (same project, dual emit).
3. **Do not** introduce Taro, React Native, or a second mini-program stack without a **new ADR**.
4. **Do not** rewrite the operator console in uni-app.
5. End-user auth = Bearer / OP tokens — **not** the console Redis / httpOnly operator session.

See [`docs/adr/0007-client-triad.md`](docs/adr/0007-client-triad.md) and [`clients/README.md`](clients/README.md).

新增或改 UI 前先判定：操作员控制台 → `web/`；终端用户 H5/小程序 → `clients/uni-app/`。禁止把 C 端塞进控制台；禁止无 ADR 另起 Taro/RN。

## Console (`web/`) notes

`web/AGENTS.md` may contain Next.js-generated agent rules. Prefer reading `node_modules/next/dist/docs/` when editing the console.

## Java / platform

Follow `ARCHITECTURE.md`, ArchUnit boundaries, and Flyway dual-vendor rules. Domain actions: [`docs/contributing-domain-actions.md`](docs/contributing-domain-actions.md).
