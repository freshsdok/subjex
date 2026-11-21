# ADR 0015 — Authorized object access / 对象存储授权访问

- Status: **Accepted** (direction) — implementation via WP3-1… slices; **this ADR is docs only**
- Date: 2026-11-17 (Asia/Shanghai)
- Relates: ADR 0002（entity blob + `ObjectStorage` SPI）、ADR 0004（单一 `AuditPort` + `/api/v1/audit` 查询）、ADR 0001 / 0006（Membership / 组织关系 **不是** 授权）、ADR 0009（站内信附件 MIME 白名单与 5 MiB 上限）、ADR 0007 / 0014（控制台 `web/` vs 终端 `clients/uni-app/`；`OperatorPrincipal` vs `EndUserPrincipal`）、WP1-4（生产默认 S3 兼容存储）
- Amends: ADR 0002（对象字节仍走同一 SPI，但下载改为短时签名链接，并在端口之上增加所有者/显式授权）；ADR 0009 §「Per-file max」（5 MiB 为当时 MVP；本 ADR 将通用对象上限至少提到 50 MiB，站内信/实体附件路径在实现切片中对齐或显式迁移）
- 状态：**方向已接受** — 由 WP3-1… 切片落地；**本 ADR 只定方向，不改代码**
- 日期：2026-11-17（Asia/Shanghai）

## Context / 背景

The locked Subjex base work asks to keep the existing local-disk and S3-compatible ports, with production on S3 and keys still isolated by tenant (WP1-4 already defaulted `prod`/`production` to S3). Chunked upload should take over the current console ~5 MiB cap and support at least **50 MiB** per file (example payload sizes include PDF and imaging reports — these are size examples only, **not** medical document types in the kernel). Downloads are short-lived signed links. Access is allowed only for the **owner subject** or an **explicit grant** to a subject or an organization; missing rights are denied. Upload, download, and grant changes are audited. The MIME whitelist is kept. Acceptance: an unauthorized subject cannot download another subject's file; an authorized download can be traced in audit query; under multi-instance deploy, bytes live on shared storage, not a single disk.

底座工作已锁定：保留本地盘与 S3 兼容端口，生产走 S3，键仍按租户隔离（WP1-4 已把生产默认改为 S3）。分片上传须接管当前控制台约 5 MiB 上限，单文件至少 **50 MiB**（PDF、影像报告等只作为载荷体积举例，**不**把医疗文档类型写进内核）。下载为短时签名链接。访问须通过**所有者主体**或对主体/组织的**显式授权**，缺权即拒绝。上传、下载、授权变更写入审计；保留 MIME 白名单。验收：未授权主体无法下载他人文件；已授权下载可被审计查询追溯；多实例部署下文件在共享存储而非单盘。

Inventory of what already exists is in [`docs/storage/wp3-object-auth-progress.md`](../storage/wp3-object-auth-progress.md) (file paths there). In short:

盘点详见进度说明（含路径）。摘要：

- The blob port already exists: `ObjectStorage` (`put` / `find` / `delete`) in `platform-contract` … `/storage`, with `LocalDirectoryObjectStorage` and `S3ObjectStorage` (AWS SDK v2, MinIO-compatible) selected by `ObjectStorageFactory`. Keys are `{tenantId}/{objectKey}` (single-segment names). Production refuses `type=local` and fail-fasts on missing S3 settings (WP1-4).
- 对象端口已存在；本地盘与 S3 两套实现；生产拒绝本地盘。键为 `{tenantId}/{objectKey}`。
- Upload size is capped at **5 MiB** in three places that already agree: `JdbcEntityBlobStore.MAX_BYTES`, `InboxAttachmentBind.MAX_BYTES`, and `spring.servlet.multipart.max-file-size: 5MB`. The console copy and i18n say the same. CHUNK-1 already has init/putChunk/complete against a **local temp** directory (`platform.storage.chunk-upload-directory`, default `object-store-chunks`); complete **assembles** then calls `ObjectStorage.put`. There is **no** AWS S3 multipart protocol and **no** presigned URL today. Download is an authenticated `GET` that streams bytes through the API.
- 当前上限 5 MiB。分片会话在本地临时目录组装后再 `put`。没有 S3 multipart 协议，也没有签名下载链接。
- MIME allowlist exists for **inbox attachments only** (`InboxAttachmentContentTypes`, ADR 0009). Entity blobs accept any `contentType` (default `application/octet-stream`) with only a length check.
- MIME 白名单只覆盖站内信附件；实体附件目前不校验类型。
- Authorization today is **not** owner-or-grant: entity blobs use `OperatorPrincipal` + `DeclarationAccess.requireVia` (`blob-read` / `blob-write`); inbox download is `inbox.read` + own entry. `entity_blob` has no owner subject and no grant table. Upload/download of these bytes are **not** written through `AuditPort` today (the port and `GET /api/v1/audit` already exist for other actions).
- 现有鉴权不是「所有者或显式授权」。审计端口与查询已存在，但对象上传/下载尚未写入。
- Chunk sessions (`LocalDirChunkedBlobUploadStore`) and inbox staging (`InboxAttachmentStagingStore`) keep **in-process** indexes plus local temp / ObjectStorage bytes. They are not a multi-instance session plan.
- 分片会话与站内信暂存的内存索引不是多实例方案。

Constraints: strengthen the generic platform only. Do not put hospital catalogs, appointments, visa escort, or insurance into the kernel. Fail-closed when tenant or org scope is missing. `web/` stays the operator console; end-user clients stay `clients/uni-app/`.

约束：只加强通用平台；不把医院目录、预约、签证陪诊或保险写入内核。租户/组织范围缺失则失败关闭。`web/` 仍是操作员控制台；终端客户端仍是 `clients/uni-app/`。

## Decision / 决策

### D1 — Keep both backends; authorization sits above the blob port

Reuse `ObjectStorage`, `LocalDirectoryObjectStorage`, and `S3ObjectStorage`. Dev / `local` / no-profile stays directory storage; `prod`/`production` stays S3-compatible and still refuses local disk (WP1-4). Tenant key prefix `{tenantId}/{objectKey}` is unchanged.

Authorization, signed-link issuance, MIME checks, size caps, and audit live in a layer **above** the port, so both backends behave the same. The SPI is not extended into a CDN, and implementations are not required to know about grants.

复用现有端口与两端实现。授权、签名链接、MIME、上限与审计放在端口之上，两端行为一致。不把 SPI 做成 CDN，实现类不必感知授权。

### D2 — Owner subject, or an explicit grant to a subject or an organization (deny by default)

Each object in this model records an **owner subject** at upload (the authenticated actor's `subjectId`). Download (and signed-link issue) is allowed only when the actor is that owner, **or** there is an **explicit**, unexpired, tenant-scoped grant whose grantee is:

每个对象在上传时记录**所有者主体**。下载（及签发签名链接）仅当行动者是所有者，或存在未过期、且限定在该对象所属租户内的**显式授权**，被授权方为：

1. a **subject**, or
2. an **organization**.

Missing tenant, missing object, missing owner, unknown/disabled tenant, or no matching grant is **fail-closed** (deny). An organization grant is evaluated at access time against **current ACTIVE `Membership`** of the actor in that organization, in the same tenant (or the tenant's bound orgs via existing `TenantOrganization` rules). Membership or an org relationship **alone is not authorization** — that matches ADR 0001 (`membership ≠ authorization`) and ADR 0006. The grant row is the authorization fact; membership only answers “is this subject in the granted organization right now?” Revoked membership loses access; deleting the grant also loses access.

缺租户、缺对象、缺所有者、租户未知/停用、或没有匹配授权，一律失败关闭。组织授权在访问时按该组织当前 ACTIVE Membership 判断。仅有成员关系或组织关系**不是**授权，与本体一致。授权行才是授权事实。

Grants do not cross tenants. This ADR does **not** introduce unauthenticated public share links, or a general “anyone in my org can see all files” switch.

授权不跨租户。本 ADR **不**引入未认证公开分享链接，也不引入「组织内人人可见所有文件」的总开关。

Existing entity-blob routes (`PolicyEngine` `blob-read`/`blob-write`) and inbox “own entry” download are **not** rewritten in this docs slice. Implementation slices must migrate the paths covered by the acceptance tests onto deny-by-default owner-or-grant (or issue an equivalent system grant, e.g. inbox recipients at send time). `entity_blob` rows today have no owner: backfill vs. apply-only-to-new-uploads is left to those slices and must stay fail-closed for unowned objects that have been moved onto this model. Do not invent a retroactive owner.

现有实体附件与站内信「本人收件」路径本切片不改写；由实现切片迁到本模型（或在发送时写入等价系统授权）。已有无所有者行如何回填留给实现切片，迁入本模型后无所有者必须失败关闭。

### D3 — Short-lived signed download links

Bytes are not the long-term download UX. After the D2 check succeeds, the platform **issues a short-lived signed link**:

鉴权通过后签发短时签名链接，而不是把长期暴露字节流当作产品下载方式：

- **Default TTL: `PT15M`**, configurable, with a hard cap of **`PT1H`** (longer values are refused). The link binds at least object identity and expiry.
  默认 TTL 为 15 分钟，可配置，硬顶 1 小时。
- **Production (S3-compatible):** use an **S3 presigned GET** for the already-authorized object key. The client then fetches from the shared store. The platform must not ship bucket credentials to the client.
  生产用 S3 预签名 GET；不得把桶凭证交给客户端。
- **Local disk (dev):** the platform issues an **application signed URL** (HMAC or equivalent over object id + expiry, verified by the app) that streams from `LocalDirectoryObjectStorage`. This is the local equivalent, not a second authorization model.
  本地盘由应用签发等价签名 URL，再从本地实现读出。
- Issuance always happens **after** D2. A raw `ObjectStorage.find` must not be exposed as a public unauthenticated download API.
  必须先鉴权再签发；不得把裸 `find` 当成公开下载。

Because an S3 presigned GET may not hit `platform-app` again, **audit is recorded when the signed link is authorized and issued** (see D5). That is the queryable “authorized download” fact. App-issued local GETs may additionally audit fulfillment; that is optional hardening, not a second audit channel.

S3 预签名 GET 可能不再经过应用，因此审计记在「授权并签发链接」时，作为可查询的已授权下载事实。

This is not a CDN product, not a public object website, and not a substitute for the realm gate in ADR 0014 / WP2-5.

这不是 CDN 产品，也不是公开对象站点，更不能绕过终端/操作员领域门。

### D4 — Multipart / chunked upload, cap at least 50 MiB; no CDN

Raise the generic per-file cap to **at least 50 MiB** (shared constant / `platform.storage` setting, and align `spring.servlet.multipart` plus console/inbox client checks). 50 MiB is a platform size cap for generic files (PDF, images, office documents, imaging-report-sized payloads as **examples**). Do **not** add hospital or imaging-report document types to the kernel.

将通用单文件上限至少提高到 50 MiB，并对齐 multipart 配置与控制台/站内信客户端检查。这是通用体积上限；不要把医疗/影像报告文档类型写进内核。

Reuse the existing CHUNK-1 pattern for files that should not ride a single console POST: sessions live under `platform.storage.chunk-upload-directory` (local temp); **complete assembles then `ObjectStorage.put`**. The same path works for `local` and `s3`. This ADR does **not** require the AWS S3 multipart protocol to hit 50 MiB. A later slice may add true S3 multipart if size or memory pressure demands it; that is not a WP3-0 product.

复用现有 CHUNK-1：临时目录组装后再 `put`，两端相同。达到 50 MiB **不**要求上 AWS S3 multipart 协议。

Temp chunks are not the system of record. Only a successful complete commits bytes to `ObjectStorage`. Abort/TTL purge removes temp files (CHUNK-1 already has a 1-hour session TTL). In-process session indexes today are **not** multi-instance safe; multi-instance production already requires shared **object** storage (S3) for committed bytes. Sticky routing or a later shared session store may be needed for chunked upload across replicas — this ADR does not invent a session-store product.

临时分片不是系统真源；只有 complete 成功后才写入对象存储。分片会话的进程内索引目前不是多实例方案；已提交字节在生产必须落在共享 S3。跨副本分片上传可能需要粘性路由或后续共享会话存储，本 ADR 不发明会话存储产品。

Do not build a general CDN.

### D5 — Audit upload, download (link issue), and grant changes; keep the MIME whitelist

Use the existing single `AuditPort` / `AuditEntry` (ADR 0004). Do not add a second audit channel. Record at least:

沿用单一 `AuditPort`，至少记录：

- object upload (commit / complete),
- authorized signed-link issue (the download fact; include object id in `actionTarget`),
- grant create / revoke (and equivalent system grants).

Outcomes follow existing `AuditOutcome` (success / refused). Operators can already filter `GET /api/v1/audit` by action/tenant/time; acceptance “authorized download is traceable” means these new action names are queryable there. End-user access to the operator audit API is **not** opened (WP2-5).

验收「已授权下载可被审计查询追溯」指这些动作可在现有审计查询中按动作/租户/时间筛到。不向终端令牌开放操作员审计接口。

**MIME whitelist is kept.** Inbox attachments already have `InboxAttachmentContentTypes` (PDF, PNG, JPEG, WebP, `text/plain`, Office Open XML). The authorized-object layer uses a **shared** allowlist starting from that set. New uploads on this track that are not on the list are refused. Whether existing entity-blob `application/octet-stream` uploads are tightened is an implementation-slice migration, not a silent break in this ADR.

保留 MIME 白名单；授权对象层与站内信共用、并以 ADR 0009 集合为起点。新上传不在名单则拒绝。现有实体附件的任意类型是否收紧由实现切片迁移，本 ADR 不在文档切片静默破坏既有上传。

### D6 — Operator vs end-user shells stay split

Both `OperatorPrincipal` and `EndUserPrincipal` may need file access later. They are different principals (ADR 0014). Grants and ownership are **subject- and tenant-scoped**, not “the operator token is an end-user downloader.”

操作员与终端主体都可能需要访问文件，但主体类型不可混用。所有权与授权按主体 + 租户，而不是用操作员令牌冒充终端下载者。

- `web/` remains the operator console; `clients/uni-app/` remains the end-user shell (ADR 0007). Do not put end-user download UX into `web/`, or operator management into uni-app.
- Operator object HTTP stays on operator-authenticated routes. If end-user download HTTP is added later, it must live under an end-user allowlisted prefix (e.g. `/api/v1/end-user/**`) and **must not** open operator console management APIs. The WP2-5 realm gate (`EndUserRealmGateFilter`, `reason=operator-authentication-required`) stays in force.
- An operator token is not accepted as an end-user authenticator on end-user object routes, and an end-user token is not accepted on operator management or operator object-admin routes.

控制台与终端壳分离。若后续增加终端下载 HTTP，必须走终端允许前缀，且不得打开操作员管理接口。WP2-5 领域门保持有效。

## Proposed implementation slices / 拟定实现切片

These are direction only; **do not implement them in WP3-0**.

以下只是方向，**WP3-0 不实现**。

| Slice | Intent |
| --- | --- |
| **WP3-1** | Grant schema + ports: owner subject, explicit grants to subject or organization, deny-by-default, tenant/org fail-closed. Tests for unauthorized deny. 授权表与端口；默认拒绝；缺范围失败关闭。 |
| **WP3-2** | Short-lived signed download: issue after D2; S3 presign in prod; app-issued signed URL on local disk; default TTL PT15M / hard cap PT1H. 短时签名下载。 |
| **WP3-3** | Raise per-file cap to at least 50 MiB; align CHUNK-1, Spring multipart, and console/inbox client checks; keep assemble-then-`put`; no CDN; no S3 multipart protocol required. 上限至少 50 MiB。 |
| **WP3-4** | Audit upload, signed-link issue, and grant changes via existing `AuditPort`; keep/share the MIME allowlist on the authorized-object track. 审计 + MIME 白名单。 |
| **WP3-5** | Formal tests: unauthorized subject cannot download another’s file; authorized download (link issue) is audit-queryable; tenant missing/disabled fail-closed; MIME deny. 正式隔离与审计追溯测试。 |

Optional later (not required to close the ADR acceptance if HTTP already exists for the tested principals): thin console or uni-app wiring to request a signed link; sticky/shared chunk sessions for multi-replica upload; true S3 multipart protocol if size grows; migration of historical unowned `entity_blob` rows.

可选后续：控制台/终端薄接入签名链接；多副本分片会话；真正的 S3 multipart；历史无所有者行迁移。

## Consequences / 后果

- Production multi-instance continues to depend on S3-compatible shared storage for **committed** bytes (already WP1-4). Local disk and local chunk temp remain dev / same-instance concerns.
  生产多实例的已提交字节继续依赖 S3 共享存储。
- Callers stop treating a long-lived authenticated byte GET as the only download API; they request a short-lived link after authorization.
  下载改为鉴权后的短时链接。
- Org membership still does not, by itself, open files. Operators must grant explicitly.
  组织成员关系本身打不开文件。
- End-user tokens remain locked out of operator management APIs (ADR 0014 / WP2-5).
  终端令牌仍不能调用操作员管理接口。

## Out of scope / 非目标（本 ADR 不发明的产品）

- Hospital catalogs, appointment/revenue share, visa escort, insurance, or a “patient document type” catalog in the kernel. PDF / imaging-report sizes are examples only.
- A CDN, public anonymous share links, or cross-tenant grants.
- A virus-scanning product or in-browser previewer (already out of ADR 0009).
- Rewriting `web/` in uni-app, or mixing console and end-user shells.
- Changing WP1-4 production fail-fast / no-local-disk rules.
- A general billing product, or automatically creating tenants/orgs on upload.

## Open points not settled from existing docs / 现有文档无法锁死、故不臆造

- **Exact HTTP path shape** for a future end-user download API — only the constraint that it stay on an end-user allowlisted prefix and must not open operator management APIs.
- **Historical `entity_blob` owner backfill** vs apply-to-new-uploads-only, and whether inbox “own entry” becomes a system grant row or stays a specialized check until a later unify slice.
- **Whether AWS S3 multipart protocol** is adopted after 50 MiB (not required to meet the locked cap).
- **Multi-replica chunk-session sharing** (sticky vs a future shared session store) — committed bytes are already required to be on S3; the session store is not specified here.
- **SMS / virus / preview / public link** product choices remain out of this ADR.

## References / 引用

- Inventory: [`docs/storage/wp3-object-auth-progress.md`](../storage/wp3-object-auth-progress.md)
- Current backends: [`docs/storage/object-storage.md`](../storage/object-storage.md)
- WP1-4 production default: [`docs/release/wp1-prod-hardening-progress.md`](../release/wp1-prod-hardening-progress.md)
- ADR 0002, 0004, 0009, 0014
