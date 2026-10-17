# ADR 0009 — Inbox attachments, groups, and mass send / 站内信附件·分组·群发

- Status: **Accepted**（2026-10-17 Asia/Shanghai；用户 accept-recommended-defaults 锁死 5 条；IM-A1 可开工）
- Date: 2026-10-17 (Asia/Shanghai)
- Relates: ADR 0008（thin mailbox Accepted）、ADR 0002（ObjectStorage / entity_blob）、ADR 0006（org / OrgScope）、CHUNK-1（分片上传）、[`docs/messaging/im-progress.md`](../messaging/im-progress.md)

## Context / 背景

ADR 0008 MVP（IM-1…IM-5，tip ~`47fe668`）已交付：

| 已有 | 能力 |
| --- | --- |
| `inbox_message` + `inbox_entry`（Flyway V43） | 正文一次 + 每人一条 + `read_at` |
| Channels | `SYSTEM` \| `WORKFLOW` \| `ORG_BROADCAST`（**仅枚举占位，无 API**） |
| Permissions | `inbox.read` / `inbox.send.system` |
| Producers | 系统发送 API + 报修工作流钩子 |
| Console | `/inbox` 列表 + 顶栏未读角标 |

**缺口（产品新需求）：**

1. 发送时支持 **文件 + 文字**（附件，非邮件 MIME 引擎）
2. **用户分组**（可维护收件人组）
3. **群发**（按组 / 按组织批量写扩散）

### Inventory used / 可复用盘点

| Surface | Exists? | Relevance |
| --- | --- | --- |
| `ObjectStorage` + `entity_blob`（V31） | Yes | Bytes 外置；元数据绑 `entity_key`/`record_id` — **不宜直接塞站内信** |
| CHUNK-1 `…/blobs/uploads` | Yes | 分片会话 → assemble → `ObjectStorage.put`；上限 **5 MiB**/文件 |
| Org Membership + OrgScope | Yes | 可算「某组织节点下主体」；**不是**邮件列表产品 |
| Outbox / `DeliveryPort` | Yes | 仍只做外推；**不当收件箱** |
| Thread / chat | **No** | 0008 非目标；本 ADR **不松口** |

盘点结论：附件字节走现有对象存储；站内信用**独立附件元数据表**引用 `storage_key`；群发激活 / 扩展渠道并设 **bound N**；分组建议独立 `inbox_group`（组织广播作第二目标源）。

## Decision / 决策（Accepted）

### D1 — Keep thin mailbox; attachments are not chat

- 仍是 **站内信箱**：SYSTEM / WORKFLOW / 群发类通知；**不做** Thread、回复链、会话室。
- 消息 = `title` + `body`（文字）+ **0..K 附件引用**；附件不是邮件 MIME 组装，也不进 `body` 大字段 / Base64。
- 工作流钩子（IM-3）首波**不强制**带附件；附件主要服务人工「发信 / 群发」与后续 SYSTEM 扩展。

薄信箱立场不变；附件 = 外置对象引用，不是聊天文件气泡产品。

### D2 — Attachment model / 附件模型

```text
inbox_message  1 ─── *  inbox_attachment
                              │
                              ▼
                     ObjectStorage (storage_key)
```

**Proposed schema（illustrative；下一空闲 Flyway after V43）：**

```text
inbox_attachment (
  attachment_id     PK,
  message_id        FK → inbox_message,
  tenant_id         NOT NULL,          -- denormalized; fail-closed
  file_name         NOT NULL,          -- display name (sanitized)
  content_type      NOT NULL,
  byte_size         NOT NULL,
  storage_key       NOT NULL,          -- ObjectStorage key (tenant-prefixed path)
  checksum_sha256   NOT NULL,
  sort_order        NOT NULL DEFAULT 0,
  created_at        NOT NULL
)

INDEX (message_id, sort_order)
INDEX (tenant_id, storage_key)
```

| Rule | Proposed |
| --- | --- |
| Bytes location | **Reuse `ObjectStorage`**（local / S3）；与 entity blob 同 SPI |
| Metadata table | **New `inbox_attachment`** — **do not** overload `entity_blob`（缺 entity/record 语义） |
| Upload UX | Reuse CHUNK-1 pattern: init → chunks → complete **into a draft/staging key**, then bind on send; small files may single-shot PUT |
| Per-file max | Align entity blob MVP：**5 MiB**（`JdbcEntityBlobStore.MAX_BYTES`）；raise = later product |
| Per-message max files | **≤ 5** attachments（建议） |
| Allowed types | Allowlist MVP：`application/pdf`, `image/png`, `image/jpeg`, `image/webp`, `text/plain`, Office openxml (`docx`/`xlsx`/`pptx`)；其余 **415/400** |
| Not in scope | Email MIME engine、病毒扫描产品、在线预览器、跨租户共享链接 |

**Download AuthZ：** 仅当调用者对某 `inbox_entry`（同 `message_id`）有 **本人收件** 资格，或持有发送审计权限的管理员在本租户内查看自己发出的消息附件 — **默认：收件人只能下本人可见消息上的附件**（开放决策 #5）。

发送流程（建议）：

```text
1) POST /inbox/attachments/uploads … (chunked) → staging storage_key(s)
2) POST /inbox/system | /inbox/broadcast | /inbox/group-send
      { title, body, recipient…, attachmentIds[] }
3) same TX: inbox_message + inbox_attachment rows + inbox_entry fan-out
```

未绑定的 staging 对象：TTL 清理（对齐 chunk session 1h 思路）。

### D3 — Group model / 分组模型（MVP 建议）

**建议默认：专用站内信组（mailing list），组织广播作第二目标。**

```text
inbox_group (
  group_id      PK,
  tenant_id     NOT NULL,
  name          NOT NULL,
  description   NULL,
  realm         NOT NULL,   -- OPERATOR first; END_USER later
  created_by    NOT NULL,
  created_at    NOT NULL,
  updated_at    NOT NULL
)

inbox_group_member (
  group_id              FK,
  tenant_id             NOT NULL,
  member_realm          NOT NULL,
  member_subject_id     NOT NULL,
  added_at              NOT NULL,
  PRIMARY KEY (group_id, member_realm, member_subject_id)
)
```

| Target kind | MVP? | How recipients resolved |
| --- | --- | --- |
| **Explicit subject list** | Yes (already SYSTEM send) | Caller passes `recipientSubjectIds[]` |
| **`inbox_group`** | **Yes（本 ADR 主推）** | Expand ACTIVE members at send time → fan-out |
| **Org unit / OrgScope subtree** | **Yes as ORG_BROADCAST** | Membership under org node(s) ∩ sender OrgScope（fail-closed） |
| Dynamic role DSL | **No** | 0008 non-goal |

**为何不只复用组织：** 组织树是编制/授权边界；运维常需「值班组 / 发布通知组」等**横切名单**，与部门不完全重合。专用组 + 组织广播两者都要，但 **CRUD 组** 是产品「用户分组」的直接答案。

组管理权限：见 D6（`inbox.group.admin`）。

### D4 — Mass send / 群发

Activate / extend channels:

| Channel | Meaning | API |
| --- | --- | --- |
| `SYSTEM` | 点对点 / 小名单系统通知（可带附件） | 扩展现有 `POST /inbox/system` 支持 `attachmentIds` |
| `ORG_BROADCAST` | 按组织目标群发（解锁 IM-7） | `POST /inbox/broadcast` `{ orgIds[], includeDescendants?, … }` |
| `GROUP_SEND`（**new enum**） | 按 `inbox_group` 群发 | `POST /inbox/group-send` `{ groupId, … }` |

（若不愿新增枚举，可将组发送记为 `SYSTEM` + `producer_kind=group`；**建议显式 `GROUP_SEND`** 便于列表过滤与审计。）

**Fan-out strategy（Proposed defaults — open decision #3）：**

| Resolved recipient count N | Behavior |
| --- | --- |
| `N ≤ 200`（sync bound） | **Same TX**：`inbox_message` + attachments + all `inbox_entry` |
| `200 < N ≤ 2000`（hard cap） | Same TX write message + attachments；**async job** inserts entries in batches（progress row optional）；caller gets `messageId` + `fanoutStatus=PENDING` |
| `N > 2000` | **Reject 400** with clear error（raise cap later） |

- **Truth remains tables**：async job still writes `inbox_entry` rows； Outbox **不是**收件箱。
- Optional：async 完成后 Outbox 事件仅用于外推（IM-8 仍后置）。
- Dedup：同一 `(message_id, realm, subject)` UNIQUE；组成员重叠只保留一条。
- Empty expansion → **400**（无静默成功）。

写扩散仍是真相；大 N 用库内异步批次，不把邮件当信箱。

### D5 — Console-first send UI sketch / 控制台草图

| Surface | Scope |
| --- | --- |
| `/inbox` | 现有收件列表 + 附件下载入口（有附件图标） |
| `/inbox/compose`（新） | 标题/正文、附件上传、收件人模式切换：**手动主体** \| **站内信组** \| **组织节点** |
| `/inbox/groups`（新） | 组 CRUD + 成员增删（OPERATOR realm） |
| Header badge | 不变（未读仍 ~60s） |
| uni-app / END_USER | **后置**（对齐 0008 IM-6）；组 realm 预留 |

不做：聊天气泡 UI、已读回执花活、富文本邮件编辑器（正文保持 plain / 短 markdown）。

### D6 — AuthZ / 授权

| Permission | Effect |
| --- | --- |
| `inbox.read` | 读本人条目；**下载本人条目所属消息的附件** |
| `inbox.send.system` | 系统发送（可带附件）；小名单 |
| `inbox.broadcast`（**new**） | `ORG_BROADCAST` / 组织群发 |
| `inbox.group.admin`（**new**） | 组 CRUD + 成员维护 |
| （no separate `inbox.group.send`） | **Locked #4:** `GROUP_SEND` uses `inbox.broadcast` |

- Tenant grant fail-closed（`X-Tenant-Id`）。
- Org broadcast：目标组织 ∈ sender **OrgScope**（SELF_AND_DESCENDANTS）；越权组织 → **403**。
- Group send：组必须同租户；成员展开后仍写本租户 entries。
- 跨 subject 读附件 / 条目 → **403**（与 0008 一致）。

### D7 — API sketch (additive)

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/inbox/attachments/uploads` | 分片 init（staging） |
| `PUT` | `/api/v1/inbox/attachments/uploads/{id}/chunks/{i}` | chunk |
| `POST` | `/api/v1/inbox/attachments/uploads/{id}/complete` | → staging attachment id |
| `GET` | `/api/v1/inbox/entries/{entryId}/attachments/{attachmentId}` | download（own entry only） |
| `POST` | `/api/v1/inbox/system` | **extend** body: `attachmentIds?` |
| `POST` | `/api/v1/inbox/broadcast` | ORG_BROADCAST |
| `POST` | `/api/v1/inbox/group-send` | GROUP_SEND |
| `GET/POST/PATCH/DELETE` | `/api/v1/inbox/groups…` | group + members CRUD |

### D8 — Migration / port sketch

- Flyway **V44+**：`inbox_attachment`；`inbox_group` + `inbox_group_member`；permissions inserts；optional `channel` check / enum doc for `GROUP_SEND`.
- `InboxPort.send`：accept attachment refs；new `sendBroadcast` / `sendToGroup` or unified target descriptor.
- Staging cleaner scheduled task（optional small slice）。
- Tests：isolation（tenant/subject）、cap reject、OrgScope deny、group expand dedup、download 403。

## Non-goals / 非目标

- Chat Thread / DM / 群房间
- 把 `entity_blob` 行直接当站内信附件（语义错绑）
- 邮件 MIME / SMTP 发送引擎
- 无限人数同步写扩散
- END_USER 组与终端发信 UI（后置）
- 替换 Outbox / Audit

## Alternatives considered / 备选

| Option | Why not (now) |
| --- | --- |
| Attach files as Base64 in `body` | 撑爆行；无法独立鉴权下载；无 checksum |
| Reuse `entity_blob` with fake entity_key=`inbox` | 污染实体附件语义；erase/quota 路径纠缠 |
| Org-only groups (no `inbox_group`) | 无法表达横切通知名单 |
| Always-async fan-out via Outbox-as-inbox | 违反 0008；UI 依赖队列 |
| Full IM product | Scope 爆炸 |

## Locked decisions / 已锁定决策（原 Open decisions）

Accepted 2026-10-17 (Asia/Shanghai) after user **accept-recommended-defaults** for all 5 items.

用户选择「全部按建议默认」→ 锁死：

| # | Topic | Locked answer | Rationale (one-liner) |
| --- | --- | --- | --- |
| 1 | Attachment storage | **ObjectStorage + `inbox_attachment`**；不复用 `entity_blob` | 实体附件语义不污染站内信；同 SPI 运维 |
| 2 | Group model | **Both**: dedicated `inbox_group` **and** org `ORG_BROADCAST` | 横切名单 + 编制树广播 |
| 3 | Mass-send cap / fan-out | **Sync ≤200**；**async ≤2000**；above → reject | 小名单同 TX；中等异步仍写 `inbox_entry`；硬顶防炸库 |
| 4 | Who can send | `inbox.send.system` 可带附件；群发需 **`inbox.broadcast`**；组 CRUD 需 **`inbox.group.admin`** | 最小权限拆分；`GROUP_SEND` 共用 `inbox.broadcast`（不另加 `group.send`） |
| 5 | Attachment download | **Own entry only**（本人收件条目所属消息附件） | 与 0008 本人隔离一致；发送者回看 = IM-A8 可选 |

## Implementation map / 切片地图（IM-A*）

| Slice | Intent | Depends |
| --- | --- | --- |
| **IM-A0** | This ADR + progress board | — |
| **IM-A1** | Flyway attachment + groups schema + contract/port + download AuthZ | Locked #1 #2 #5 |
| **IM-A2** | Staging / chunk upload API + system send w/ attachments | IM-A1 |
| **IM-A3** | `inbox_group` CRUD REST + `inbox.group.admin` | IM-A1 |
| **IM-A4** | GROUP_SEND API + sync fan-out (≤200) | IM-A2, IM-A3, Locked #3 #4 |
| **IM-A5** | ORG_BROADCAST API + OrgScope gate（原 IM-7） | Locked #2 #3 #4 |
| **IM-A6** | Async fan-out job when 200 < N ≤ 2000 | IM-A4/A5 |
| **IM-A7** | Console compose + attachment UX + groups admin | IM-A2…A5 |
| **IM-A8** | （可选）已发送箱 / 发送者回看附件 | product ask |

**IM-A1 unblocked** — schema-facing decisions are locked.

## Consequences / 后果

**Positive**

- 附件与实体存储同 SPI，运维一致（local/S3）。
- 分组与组织解耦，群发可审计、可限额。
- 保持 0008 薄信箱 + 租户 fail-closed。

**Negative / cost**

- 新表与权限；异步扩散增加运维面（Locked #3：200–2000）。
- 控制台需 compose / groups 两页。
- 5 MiB 上限可能不够大文件场景（需另提额）。

## Links / 链接

- Parent: [`0008-in-app-messaging.md`](0008-in-app-messaging.md)
- Progress: [`docs/messaging/im-progress.md`](../messaging/im-progress.md)
- Storage: [`docs/storage/object-storage.md`](../storage/object-storage.md)
- Entity blobs / CHUNK-1: [`docs/entity-storage-es-progress.md`](../entity-storage-es-progress.md)
