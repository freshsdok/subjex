# End-user auth + IM-6 progress / 终端认证与 IM-6 进度

- Order #3 (autonomous 1→6) — Asia/Shanghai 2026-10-22
- Relates: ADR 0007, ADR 0008 IM-6, IdP wechat:mini (ID-4)

> **WP2-6 (2026-11-17) superseded the `#3a` operator-token path.** The uni-app shell no longer calls `POST /api/v1/auth/login` or `POST /api/v1/auth/idp/wechat/callback`, and no longer treats operator Bearers as `subjex_end_user_*` tokens. Use `/api/v1/end-user/auth/**` and `eu.at.` / `eu.rt.` only. Track the closed WP2 package in [`docs/identity/wp2-end-user-progress.md`](../identity/wp2-end-user-progress.md) (feature SHA `5ed46c1`). Inbox `/api/v1/me/inbox` and AUTH_END_USER questionnaire listing remain residual (still `OperatorPrincipal` on the server).
> **WP2-6 已替换 `#3a` 把操作员令牌伪装成终端路径的做法。** 请以 WP2 进度说明为准。

## Slices

| ID | Status | Notes |
| --- | --- | --- |
| **3a** | **done** (local) | uni-app password login + wechat:mini jsCode → IdP callback; Bearer in storage |
| **3b / IM-6** | **done** (local) | `/api/v1/me/inbox` END_USER read path + uni-app inbox page |

## E2E (local / demo)

1. Sign in on uni-app login (H5 password against `platform-operator` / linked WeChat mini).
2. SYSTEM send with `"recipientRealm":"END_USER"` to that subjectId.
3. Open `/pages/inbox/inbox` with tenant → list / mark-read.

## Non-goals this wave

- Dedicated END_USER account table / auto-provision on WeChat
- Operator Redis session reuse
- uni-app compose / fan-out UI

These non-goals described **order #3 / IM-6**. First-class end-user accounts, WeChat auto-provision, token isolation, and the uni-app client are now [ADR 0014](../adr/0014-end-user-identity.md) / [`docs/identity/wp2-end-user-progress.md`](../identity/wp2-end-user-progress.md) (WP2, closed at WP2-6 `5ed46c1`). The `#3a` path that called operator `/api/v1/auth/login` has been replaced; do not treat this note's E2E steps (password login as `platform-operator`) as the current product path.
以上非目标仅描述 **order #3 / IM-6**。`#3a` 调用操作员登录的路径已在 WP2-6 替换；本文原来的口令登录 E2E 不再是产品路径。
