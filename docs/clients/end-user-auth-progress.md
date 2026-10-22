# End-user auth + IM-6 progress / 终端认证与 IM-6 进度

- Order #3 (autonomous 1→6) — Asia/Shanghai 2026-10-22
- Relates: ADR 0007, ADR 0008 IM-6, IdP wechat:mini (ID-4)

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
