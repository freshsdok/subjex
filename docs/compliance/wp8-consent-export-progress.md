# WP8 consent and export-approval hooks progress / 同意与导出审批钩子进度

- Date: 2026-11-20 (Asia/Shanghai); async generation deepening 2026-11-26; volume leftovers (history + lease) 2026-11-28
- ADR: [`docs/adr/0020-consent-and-export-hooks.md`](../adr/0020-consent-and-export-hooks.md)
- Baseline tip when this note was opened: `4ea515a` (WP7 end-user shell package closed; formal acceptance `bf30bdf`)
- Predecessor: ADR [`0014`](../adr/0014-end-user-identity.md) (EndUserPrincipal, `/api/v1/end-user/**`), ADR [`0015`](../adr/0015-object-storage-authorization.md) (ObjectGrantStore, signed download), ADR [`0016`](../adr/0016-thin-reusable-workflow.md) (declarable thin workflow), ADR [`0004`](../adr/0004-observability-audit-trace.md) (`AuditPort`), ADR [`0003`](../adr/0003-user-center-oidc.md) (OAuth consent — **not** this package). Tenant ZIP export / hard erase: [`docs/tenant/tn-progress.md`](../tenant/tn-progress.md) (TN-2). Locked base-work canvas: 「Subjex 底座工作需求」WP8 section (read this slice).
- 本切片提交：`483bf10`（`483bf107d3407667ecaf75affaf025512e418e90`；WP8-4 正式验收并**关闭 WP8 包**）。提交落在当前 tip 之上（含已落地的 WP9-0）。不启动 WP9-1。未改 uni-app / `web/`。

This track adds generic **consent records** and **export-approval hooks**: a subject can grant or withdraw consent for a purpose (tenant-supplied purpose key + consent-text version id, timestamps, audit, queryable history), and a batch / sensitive personal-data export must pass an approval hook, be audited, and (when approved) produce an artifact redeemable as a short-lived signed download via WP3. The kernel does not embed legal opinions, legal-text content, or medical cross-border compliance judgments. It must stay generic: no hospital/medical, visa, or insurance wording.

本轨增加通用的同意记录与导出审批钩子：主体可对某目的授予或撤销同意（租户提供的目的键与同意文本版本标识、时间、审计、可查询历史）；批量或敏感个人数据导出须经审批钩子并留痕，获准后经 WP3 短时签名下载。内核不写法律意见、法律正文或医疗跨境合规判定。必须保持通用：无医院/医疗、签证或保险用语。

## Product deepening [done] — async generation and keyset pages

A later product-deepening slice (after WP8 close) moves generation off the request thread. Approval auto-enqueues `GENERATING`. A thin `@Scheduled` `ExportGenerationWorker` (same style as `PaymentExpiryWorker`) calls `ExportGenerationService.processQueued`, streams the artifact into object storage, and completes to `READY` or fails to `FAILED`. `POST .../generate` only enqueues or retries; it does not write the artifact. Lists are tenant-scoped keyset pages (`{exports, nextCursor}`). Artifact TTL remains 7 days on `READY`. Self-approval remains forbidden. Questionnaire CSV and tenant ZIP export are unchanged.

随后的产品加深切片（WP8 关包之后）把生成移出请求线程。批准后自动入队 `GENERATING`。薄定时 `ExportGenerationWorker`（风格同 `PaymentExpiryWorker`）调用 `ExportGenerationService.processQueued`，把产物流式写入对象存储，成功为 `READY`，失败为 `FAILED`。`POST .../generate` 只入队或重试，不写产物。列表改为租户内键集分页（`{exports, nextCursor}`）。产物仍是 7 天后过期。禁止自己批准自己。问卷 CSV 与租户 ZIP 导出未改。

Honest limits / 诚实边界：million-row readiness is for inbox / questionnaire responses / owned objects **and consent history** in the generic exporter (keyset + stream), not million QPS and not Elasticsearch. Current consents remain a short purpose list. Third-party exporters that only implement `export()` may still buffer. Generation uses a claim/lease so `FOR UPDATE` is not held for the whole stream; an expired lease can be reclaimed. Mid-write resume is not added — a reclaim overwrites the object from scratch. Questionnaire CSV and tenant ZIP still build in memory on the request thread (a later cut). Export-request lists are capped and cursor-paged, not a million-request search product.

随后切片：同意历史已按键集流式写出；生成改为认领/租约。问卷 CSV 与租户 ZIP 仍待后续切片。

## WP8-4 [done] — formal acceptance; WP8 package closed

This slice locks formal acceptance of consent records and export-approval hooks and **closes the WP8 package**. It lands on top of the current main tip (WP9-0 already present). WP9-1 (audit export / SIEM) is not started. No uni-app / `web/` pages were added. No production code change.

本切片锁定同意记录与导出审批钩子的正式验收，并**关闭 WP8 包**。提交叠在当前 main tip 之上（WP9-0 已存在）。未启动 WP9-1。未改 uni-app / `web/`。无生产代码修改。

Acceptance met / 验收已满足：

- A domain-style caller is blocked by `ConsentPort.require` without a current grant, passes after `grant`, and is blocked again after `withdraw`. Grant and withdraw are audited (`consent.grant` / `consent.withdraw`).
- Full export flow: end-user request → a different operator (not the requester) approves → synchronous generate → end-user short-lived signed link → redeem of the WP3 artifact.
- The acceptance fixture seeds real questionnaire responses and inbox entries for **two** subjects. The redeemed JSON bundle contains the requester's own questionnaire answer and inbox metadata, and none of the other subject's.
- Unapproved generation is denied and audited (`export.gate.deny`). Self-approval is forbidden (`export-self-approval-forbidden`). Expired requests yield no link. Cross-tenant access is denied.
- Kernel guard: production consent/export Java (comments stripped) has no medical/hospital, visa, insurance, or legal-text wording; Flyway adds no second outbox table; `WorkflowService.startFromHook` is not public.

Formal lock: `ConsentExportAcceptanceTest` 3/3. It does not repeat the WP8-1..WP8-3 focused suites.

Regression (this turn, all passed): `ConsentExportAcceptanceTest` 3/3, `ExportGenerationTest` 5/5, `ExportApprovalTest` 3/3, `ConsentPortTest` 3/3, `ThinWorkflowAcceptanceTest` 2/2, `EndUserShellAcceptanceTest` 3/3, `EndUserCrossRealmIsolationTest` 34/34. Total **53/53**. Pre-existing `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not run and was not fixed. No production code change in this slice.

### Conservative defaults kept at close (changeable later) / 关包时保留的保守默认（以后可改）

These are the defaults WP8 applied to ADR 0020 open points; they are not canvas-locked.

本包按以下默认关闭 ADR 0020 开放点；并非画布锁死。

- Sensitivity vocabulary is only `NORMAL` / `SENSITIVE`. Absent field or object metadata stays `NORMAL`; existing rows are not silently reclassified.
- Every export request needs approval, including non-bulk and non-sensitive ones.
- `ConsentPort.require`: if `requiredVersion` is given it must match the current grant exactly; if absent, any current grant for that purpose passes.
- Withdrawal is per purpose. History is append-only. The kernel stores no legal text.
- Operator generate and operator download-link reuse `export.approve` (no dedicated generate permission). Self-approval is forbidden.
- Generation is asynchronous after approval (deepening slice). Artifact TTL is request-level `expiresAt` (default P7D). Download TTL default PT15M, cap PT1H, via the existing WP3 issuer.
- No outbox notifications on consent or export (the canvas does not require them; a later notice must reuse `outbox_event`).

### Residual gaps (honest, not in this package) / 仍存缺口（本包不解决）

- Operator-console and uni-app consent/export pages later landed in the UI track; they now show `GENERATING` / `FAILED` honestly.
- Generation now uses a thin scheduled worker (`ExportGenerationWorker`); this residual is closed. Residual honesty: no new job platform, no resume/checkpoint, no Elasticsearch.
- No subject-level erasure or retention automation (TN-2 tenant hard-erase remains a separate operator lifecycle tool).
- No outbox notifications when a request is approved, ready, or expires.
- Sensitivity vocabulary is only `NORMAL` / `SENSITIVE` (no finer grades).
- Exact-version consent policy is the conservative default above; whether a newer published text invalidates a current grant is still open.
- The generic exporter covers platform data only; domain modules may register more exporters, but the kernel ships none.
- Operator download links are authorized as the owning subject after the `export.approve` gate (no separate operator SUBJECT grant).

SHA：`483bf10`（`483bf107d3407667ecaf75affaf025512e418e90`）

### Next / 下一步

WP8 is closed. The already-landed next package is **WP9-0** (observability and edge recipe). Do **not** start WP9-1 (audit export / SIEM) in this slice.

## WP8-3 [done] — exporter SPI and signed export download

This slice lands a pluggable exporter SPI, a generic platform JSON bundle, synchronous generation after approval, WP3 storage + short-lived signed download, and request-level expiry. No domain exporter, no uni-app / `web/` pages, no WP8-4.

本切片落地可插拔导出 SPI、通用平台 JSON 包、批准后同步生成、经 WP3 存储与短时签名下载，以及申请级过期。无领域导出器，无 uni-app / `web/` 页面，不启动 WP8-4。

### What landed / 已落地

- SPI: `SubjectRecordExporter` (`supports` / `export` → `ExportArtifact`; renamed from `PersonalDataExporter` so the class-name token `data` does not violate NamingGuardArchTest). Built-in `GenericSubjectRecordExporter` (formerly `GenericPlatformPersonalDataExporter`) writes a JSON bundle of the subject's own platform data: profile (`subjectName`), consent current + history, inbox entry metadata (no message body), questionnaire responses by this subject, and owned object descriptors (metadata only, no blob bytes). Sensitive sections and the artifact are marked `SENSITIVE`. Domain modules may register more exporters; the kernel adds none. Unconfigured / unsupported scope fails closed.
- Generation: `ExportGenerationService.generate` calls `ExportApprovalGate.requireApproved` (fail closed), runs a supporting exporter, stores via `ObjectStorage.put` + `object_descriptor` (owner = subject, explicit SUBJECT grant, `SENSITIVE`), moves the thin workflow and request `APPROVED` → `READY`, and records the slash-free object key `export-{requestId}`. Audited as `export.generate`. Synchronous in this slice. Artifact TTL is request-level `expiresAt` (default `P7D`, `platform.export.artifact-ttl`); past expiry refuses download links and marks `EXPIRED` on read or via a simple sweep (workflow action `expire`).
- Trigger: operator `POST /api/v1/exports/{id}/generate`. Reuses **`export.approve`** (least new permission surface; no dedicated generate permission).
- Download: end user `POST /api/v1/end-user/exports/{id}/download-link` delegates to `ObjectDownloadLinkService` (TTL default PT15M, cap PT1H). An operator may get a link with `export.approve` and an `export.download-link` audit entry (object access is evaluated as the owning subject). Unapproved, rejected, or expired requests never yield a link (`export-not-approved` / `export-artifact-unavailable` / `export-expired`).
- Flyway **V55**: `data_export_request.expires_at` and `artifact_object_key`. Workflow `data-export` adds `APPROVED --generate--> READY` and `READY --expire--> EXPIRED`.
- `WorkflowService.startFromHook` is package-private. The only public caller is `WorkflowExportHook`, which allow-lists definition/entity key `data-export`. HTTP routes do not call it. `ExportRequestService.open` goes through the hook.
- Focused `ExportGenerationTest` 5/5 (approve → generate → end-user link → redeem own JSON only; unapproved/rejected generate denied; expired no link; another end user and cross-tenant denied; `startFromHook` not public / foreign keys rejected). Regression: `ExportApprovalTest` 3/3, `ConsentPortTest` 3/3, `EndUserObjectUploadTest` 4/4, `ObjectDownloadLinkServiceTest` 19/19, `EndUserCrossRealmIsolationTest` 34/34. Total **68/68**. Pre-existing `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not run and was not fixed.

SHA：`85cc1d2`（`85cc1d2bc17d1d8b77e1e89d480eefdda0f1a985`）

### Next / 下一步

**WP8-4** — ✅ done (see close section above). **WP8 package closed.**

## WP8-2 [done] — sensitivity markers and export approval hook

This slice lands sensitivity metadata and the export-request approval hook (ADR 0020). It does **not** generate an export artifact or signed download (WP8-3). No uni-app / `web/` pages, no outbox notifications.

本切片落地敏感级别元数据与导出申请审批钩子。**不**生成导出产物或签名下载（WP8-3）。无 uni-app / `web/` 页面，不写出箱通知。

### Conservative defaults for ADR open points (changeable later) / 对 ADR 开放点的保守默认（以后可改）

These defaults are applied in this slice and may change later; they are not canvas-locked.

本切片按以下默认实现，以后可改；并非画布锁死。

- Sensitivity vocabulary is minimal: `NORMAL` / `SENSITIVE`. Declared as optional metadata on entity field definitions (`FieldSensitivity` on `EntityField`; absent → `NORMAL`) and as `object_descriptor.sensitivity` (column default `NORMAL`). Existing rows and declarations are not silently reclassified as `SENSITIVE`.
- Every export request needs approval in this package, including non-bulk and non-sensitive ones, so the hook is uniform.
- Requesters: an end user may request an export of **their own** data only (`/api/v1/end-user/exports`). An operator may request on behalf of a subject only with `export.request` (seeded on `platform-operator`, not `platform-reader`). Approval requires `export.approve`. **Self-approval is forbidden** (403, `reason=export-self-approval-forbidden`).
- Approval runs through the declarable thin workflow `workflows/data-export.workflow.yaml` (`REQUESTED` → `APPROVED` / `REJECTED`; `READY` / `EXPIRED` reserved for WP8-3) via existing `WorkflowCatalog` / `WorkflowService`. No new engine.
- `PolicyEngine` is used only for operator permission checks. `ExportApprovalGate.requireApproved(requestId)` fails closed unless the status is `APPROVED`; the denial is audited (`export.gate.deny`).
- No outbox notifications in this slice.

### What landed / 已落地

- Flyway **V54**: `data_export_request` (tenant, subject, requester realm+id, scope descriptor `ALL_OWN` or entity-key list, status, approver, timestamps, workflow instance ref) plus `object_descriptor.sensitivity` default `NORMAL`. Permissions `export.request` / `export.approve` granted to `platform-operator` only. Tenant fail-closed; reserved `platform` denied.
- Routes: end user `POST`/`GET /api/v1/end-user/exports`, `GET /{requestId}` (own subject only). Operator `POST`/`GET /api/v1/exports`, `GET /{requestId}`, `POST /{id}/approve`, `POST /{id}/reject`.
- `ExportApprovalGate.requireApproved` for WP8-3. Grant/approve/reject and gate denials go through `AuditPort`.
- `WorkflowService.startFromHook` starts the declared machine without requiring `workflow.write` (the export service has already authorized the caller).
- Focused `ExportApprovalTest` 3/3. Regression: `ConsentPortTest` 3/3, `ThinWorkflowAcceptanceTest` 2/2, `EndUserCrossRealmIsolationTest` 34/34. Total **42/42**. Pre-existing `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not run and was not fixed. No other permission-set expectations needed updating (`platform-reader` was not granted the new permissions).

SHA：`a44aad2`（`a44aad22df1da8e863c18ed6aae1e4ec0910c69b`）

### Next / 下一步

**WP8-3** — ✅ done (see close section above).

## WP8-1 [done] — ConsentPort and append-only ledger

This slice lands `ConsentPort` and JDBC storage (ADR 0020). No export pipeline, no sensitivity marks, no uni-app / `web/` pages, no WP8-2.

本切片落地 `ConsentPort` 与 JDBC 存储。无导出流水线、无敏感标记、无 uni-app / `web/` 页面，不启动 WP8-2。

### Conservative defaults for ADR open points (changeable later) / 对 ADR 开放点的保守默认（以后可改）

These defaults are applied in this slice and may change later; they are not canvas-locked.

本切片按以下默认实现，以后可改；并非画布锁死。

- `require(tenant, subject, purposeKey, requiredVersion?)`: if `requiredVersion` is given it must match the current active grant's version exactly; if absent, any current active grant for that purpose passes. Missing grant, withdrawn grant, or version mismatch throws one fail-closed type, `ConsentRequiredException` (`reason=consent-required`).
- Withdrawal is per purpose (withdraw the current grant for that purpose).
- History is append-only (grant / withdraw events only; no updates or deletes of past facts).
- The kernel does not use `oauth_consent` and stores no legal text, only a tenant-supplied `purposeKey` and `consentVersionId` (format `ConsentPort.KEY_PATTERN`, max 64 characters).

### What landed / 已落地

- Contract: `platform-contract/.../consent/ConsentPort.java` (`grant`, `withdraw`, `current`, `listCurrent`, `history`, `require`) plus `ConsentRecord`, `ConsentEvent`, `ConsentEventKind`, `ConsentRequiredException`, `ConsentCrossTenantDeniedException`.
- JDBC: `JdbcConsentPort`, registered in `PlatformWiring`. Tenant fail-closed; reserved `platform` denied; the subject must have an ACTIVE `subject_identity` in the requested tenant (otherwise `ConsentCrossTenantDeniedException`).
- Flyway **V53** `consent_event` (append-only ledger) and `consent_current` (projection kept in the same transaction).
- Audit: `consent.grant` / `consent.withdraw` via `AuditPort` only. No outbox events in this slice.
- End-user routes (`EndUserPrincipal` + `X-Tenant-Id`) under `/api/v1/end-user/consents`: list current, grant, withdraw, history — own subject only. Operator routes were skipped (not needed). Operator tokens are rejected (`end-user-authentication-required`).
- Focused `ConsentPortTest` 3/3. Regression: `EndUserShellAcceptanceTest` 3/3, `EndUserCrossRealmIsolationTest` 34/34. Total **40/40**. Pre-existing `OperatorBootstrapTest.readerRoleGetsOnlyReadPermissions` was not run and was not fixed.

SHA：`6d321db`（`6d321dbbc03d02df2b482e5c3db33f9724ca23d4`）

### Next / 下一步

**WP8-2** — ✅ done (see close section above).

## WP8-0 [done] — inventory + ADR

Docs-only. No schema, no ports, no routes, no UI in this slice.

本切片只写文档，不改表、不加端口、不加路由、不加页面。

### Canvas scope (verbatim) / 锁定需求原文

From the canvas 「Subjex 底座工作需求」, package **WP8 · 同意与导出审批钩子** (P1):

- **目标:** 为后续跨境传输和敏感数据调阅预留平台能力，不在内核写死法律意见。
- **要求 1:** 同意记录：主体、目的、文本版本、时间、撤销。
- **要求 2:** 字段或对象可标敏感级别；批量导出须经审批钩子并写入审计。
- **要求 3:** 不在 Subjex 内核实现具体医疗跨境合规判定，只提供记录与门禁。
- **验收:** 可查询某主体的同意与撤销历史；未审批的敏感数据导出被拒绝并留痕。
- **依赖（建议启动顺序）:** WP2、WP3；建议在接入真实病历文件前完成。
- **底座完工标准相关句:** 同意与导出有门禁：敏感数据导出可被拒绝并留痕，供后续合规接入。

Repo docs before this slice only name WP8 as the next package after WP7 (`docs/clients/wp7-end-user-shell-progress.md`). There is no existing `docs/compliance/` note and no purpose-consent or subject-export-approval ADR. OAuth “consent” and TN-2 tenant export/erase are different products (see inventory).

本切片前，仓库文档仅在 WP7 关闭说明中点名 WP8 为下一包。没有现成的合规进度笔记，也没有「目的同意 / 主体导出审批」ADR。OAuth 同意与 TN-2 租户导出/擦除是不同产品。

### Inventory / 盘点（事实与路径）

#### No purpose-consent store today / 今日没有目的同意存储

There is **no** kernel table or port for “subject grants/withdraws consent for a purpose with a tenant-supplied text version.” A source scan of `platform-contract` and `platform-app` finds no `ConsentPort`, no purpose-consent types, and no sensitivity / PII classification on fields or objects.

内核今天没有「主体对某目的、按租户提供的文本版本授予/撤销同意」的表或端口。也没有字段或对象上的敏感级别标记。

What *does* exist and must **not** be overloaded:

| Existing piece | Path | What it is | Why not WP8 consent |
| --- | --- | --- | --- |
| OAuth consent store (ID-3) | `platform-app/src/main/resources/db/migration/V35__oauth_consent.sql`; `platform-app/src/main/java/com/subjex/platform/app/identity/JdbcOauthConsentStore.java`; `JdbcOauthConsentChallengeStore.java`; `OauthConsentEndpoint.java`; tests `OpConsentTest.java`; ADR [`0003`](../adr/0003-user-center-oidc.md); [`docs/identity/id-progress.md`](../identity/id-progress.md) | Operator/third-party OAuth scopes: `oauth_consent` / `oauth_consent_challenge`; approve/deny at `/api/v1/oauth/consent/decision`; list/revoke `/api/v1/oauth/consent/grants` | Records *API client scopes*, not a data-processing purpose or a consent-text version. Reusing it would mix IdP consent with WP8 purpose records. |
| Questionnaire CSV export | `QuestionnairePort.exportCsv`; `platform-app/.../questionnaire/QuestionnaireEndpoint.java` | Operator export of production questionnaire responses as CSV | Not a subject-level personal-data export, and not approval-gated. Leave as-is; do not treat as the WP8 exporter. |

#### Audit (`AuditPort`) — reuse, do not add a second channel / 复用审计端口

| Piece | Path | Role |
| --- | --- | --- |
| Port | `platform-contract/src/main/java/com/subjex/platform/contract/audit/AuditPort.java` (`record(AuditEntry)`; “one port, no second channel”) | Sole audit write entry |
| Entry | `platform-contract/.../audit/AuditEntry.java` | `tenantId`, `actorIdentityId`, `actionName`, `actionTarget`, `outcome`, `occurredAt`, optional declaration linkage |
| JDBC | `platform-app/src/main/java/com/subjex/platform/app/jdbc/JdbcAuditPort.java` | Persistence |
| Operator query | `platform-app/src/main/java/com/subjex/platform/app/admin/AuditApiEndpoint.java` (`AuditQuery`, `AuditPage`, `AuditRow`) | Existing audit read-back |
| Related writers | `ObjectAccessAudit` / `ObjectDownloadIssueAuditHook` under `platform-app/.../storage/`; `OperatorActionAudit` | Object download and operator-action already leave an audit trail |

WP8 grant, withdraw, export request, approval decision, and denied unapproved export should write through this same `AuditPort`. ADR 0004 remains the audit/trace ADR.

同意授予/撤销、导出申请、审批决定、以及拒绝未审批导出，都应走这一端口，不开第二条审计通道。

#### Thin workflow (WP4) — candidate approval hook / 薄工作流可作为审批钩子

| Piece | Path | Role |
| --- | --- | --- |
| ADR | [`docs/adr/0016-thin-reusable-workflow.md`](../adr/0016-thin-reusable-workflow.md); progress [`docs/workflow/wp4-thin-workflow-progress.md`](../workflow/wp4-thin-workflow-progress.md) | Declarable states, transitions, dual approval, org-scoped approval, history; not a BPM product |
| Runtime | `platform-app/src/main/java/com/subjex/platform/app/workflow/WorkflowService.java` (`start`, `transition`, `status`; audit `workflow.start` / `workflow.transition` / `workflow.approval.*`) | Instance lifecycle |
| Catalog | `WorkflowCatalog.java`, `WorkflowDefinition.java`, `WorkflowDeclarationReader.java` | Domain modules register a new machine without special-casing the kernel (WP4 `order-approval` pattern) |
| Store / HTTP | `JdbcWorkflowStore.java`; Flyway `V38__workflow_instance.sql`, `V39__workflow_approval.sql`; `WorkflowEndpoint.java` | Persistence and operator HTTP |
| Inbox hook (repair-ticket only residual) | `RepairTicketWorkflowInboxHook.java` | Existing hook point; not a general export hook |

A new export-approval *declaration* can be registered later; this slice does not add one.

可在后续切片登记导出审批声明；本切片不加声明、不改引擎。

#### Policy engine — permission checks, not a legal engine / 策略引擎用于权限，不是合规判定器

| Piece | Path | Role |
| --- | --- | --- |
| Port | `platform-app/src/main/java/com/subjex/platform/app/security/PolicyEngine.java` (`evaluate` / fail-closed `require`) | Cedar default (`CedarPolicyEngine`) or SQL RBAC (`SqlRbacPolicyEngine` via `platform.authz.engine`) |
| Factory / checker | `PolicyEngineFactory.java`, `AccessChecker.java` | Wiring and named-permission checks |

There is no existing policy that encodes “this export is legally allowed.” The engine can gate *who may call* request/approve APIs. It must not become a medical cross-border judgment engine (canvas requirement 3).

现有策略引擎没有「本次导出是否合法」的判定。它可用来约束谁能发起或审批接口，但不得做成医疗跨境合规引擎。

#### Object grant and signed download (WP3) — reuse for the export artifact / 复用对象授权与短时下载

| Piece | Path | Role |
| --- | --- | --- |
| ADR | [`docs/adr/0015-object-storage-authorization.md`](../adr/0015-object-storage-authorization.md); progress [`docs/storage/wp3-object-auth-progress.md`](../storage/wp3-object-auth-progress.md) | Owner or explicit grant; short-lived signed URL; audit |
| Storage SPI | `platform-contract/.../storage/ObjectStorage.java`; `LocalDirectoryObjectStorage.java`, `S3ObjectStorage.java`, `ObjectStorageFactory.java` | Bytes (local / S3) |
| Grants | `ObjectGrantStore.java` / `JdbcObjectGrantStore.java`; Flyway `V50__object_grant.sql` | Register owner, SUBJECT/ORG grants, list owned-or-granted |
| Issue + redeem | `ObjectDownloadLinkService.java`, `ObjectDownloadEndpoint.java` (`POST /api/v1/objects/download-links`, `POST /api/v1/end-user/objects/download-links`, `GET /api/v1/end-user/objects/content`) | Short-lived HMAC (local) signed download |
| End-user upload/list (WP7-2) | `identity/enduser/EndUserObjectEndpoint.java`, `EndUserObjectService.java` | Related but not an export pipeline |

An approved export artifact should be stored as a granted object and downloaded through this existing issue/redeem path. No second download product.

获准后的导出产物应登记为已授权对象，走现有签发/兑换路径，不另做一套下载。

#### Outbox — reuse, no second bus / 复用出箱，不开第二总线

| Piece | Path | Role |
| --- | --- | --- |
| Event / port | `platform-contract/.../task/OutboxEvent.java`, `TaskMessagePort.java`; JDBC `JdbcTaskMessagePort.java` | Existing `outbox_event` bus |
| Relay | `platform-app/.../delivery/OutboxRelay.java`, `OutboxSocketPublisher.java` | Relay / delivery |
| Policy | `OutboxTransportPolicy.java` | Transport rules |

WP6 already locked: do not invent a second outbox or overload `idempotency_claim.task_id` / `TaskMessagePort.submit`. If WP8 later emits a domain notice (optional; not required by the canvas), it must use this same bus. This slice does not add an outbox event type.

若后续要发域通知（画布未强制），必须走现有出箱。本切片不加事件类型。

#### Tenant ZIP export / hard erase (TN-2) — related, do not overload / 租户级导出擦除不可拿来当主体导出

| Piece | Path | Role |
| --- | --- | --- |
| HTTP | `platform-app/.../tenant/TenantLifecycleEndpoint.java` — `POST /api/v1/tenants/{tenantId}/export`, `/erase/challenge`, `/erase` | Operator-triggered **whole-tenant** ZIP; challenge/confirm hard erase |
| Service | `TenantLifecycleService.java` (audit `tenant.export` / `tenant.erase.challenge` / `tenant.erase`); Flyway `V37__tenant_erase_challenge.sql` | Sync ZIP of tasks, forms, outbox, entity records/blobs; erase after SUSPEND + challenge |
| Docs | [`docs/tenant/tn-progress.md`](../tenant/tn-progress.md) | “GDPR-ish” tenant admin export/delete, not a per-subject DSAR |

This is an operator admin lifecycle tool for a tenant, not a subject-scoped personal-data export with an approval hook. Leave it in place. WP8 must not route subject exports through `TenantLifecycleService.exportZip`.

这是操作员对整租户的生命周期工具，不是带审批钩子的主体个人数据导出。保持原样，主体导出不走该 ZIP。

#### End-user realm routes / 终端领域路由

End-user tokens are allowed only on `/api/v1/end-user/**`, `/actuator/health`, and `/api/v1/questionnaires/public/**` (`EndUserRealmGateFilter`). Profile, inbox, questionnaires, and objects already live under `/api/v1/end-user/**` (WP7). New end-user consent and (own) export-request APIs, if added later, belong on that prefix. Operator approval APIs stay off it. Reserved tenant `platform` remains denied; tenant fail-closed (ADR 0014).

终端令牌仍只允许 `/api/v1/end-user/**` 等白名单。后续终端同意与本人导出申请应落在该前缀；操作员审批不放在该前缀。保留租户 `platform` 仍拒绝；缺租户失败关闭。

#### Flyway tip

Latest migration is still **V52** (`V52__payment_callback_fingerprint.sql`). This slice adds no V53.

当前库版本仍为 V52。本切片不加迁移。

### Next / 下一步

**WP8-1** — ✅ done (see close section above).

## Proposed later slices / 后续切片

See ADR 0020. Sketch:

- **WP8-1** — [done, `6d321db`] `ConsentPort` and store: grant, withdraw, query-current, append-only history, fail-closed `require`; audit; end-user routes under `/api/v1/end-user/consents`. No export yet.
- **WP8-2** — [done, `a44aad2`] sensitivity marks on fields or objects; export request lifecycle; approval through a declarable thin workflow; unapproved export is rejected and audited.
- **WP8-3** — pluggable exporter SPI (no domain exporters in the kernel); store the artifact via WP3 `ObjectStorage` + `ObjectGrantStore`; short-lived signed download; audit.
- **WP8-4** — formal acceptance and close the WP8 package.

## Open points not settled from the canvas / 画布未锁死、故不臆造

See ADR 0020. WP8-1 and WP8-2 applied the conservative defaults listed in those sections; they remain changeable later. Artifact generation, the exporter SPI, and READY/EXPIRED transitions stay open for WP8-3.

## SHA

- WP8-0：`a59da3f` (`a59da3f2e67f46a632b957a3d9111416eeb6c9c0`)
- WP8-1：`6d321db` (`6d321dbbc03d02df2b482e5c3db33f9724ca23d4`)
- WP8-2：`a44aad2` (`a44aad22df1da8e863c18ed6aae1e4ec0910c69b`)
