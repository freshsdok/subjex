# ADR 0012 — Config center deepen (post–Item 5) / 配置中心加深（Item 5 之后）

- Status: **Accepted**（2026-10-22 Asia/Shanghai；用户「请推送后启动配置中心」— MVP 已完成，锁定加深默认）
- Date: 2026-10-22 (Asia/Shanghai)
- Relates: [`docs/config/independent-config-center-plan.md`](../config/independent-config-center-plan.md)（Item 5 DONE）、`ConfigCenterPort` / `ConfigOverrideStore`、ARCHITECTURE §7/§10、[`docs/config-ui.md`](../config-ui.md)

## Context / 背景

Pre-alpha **Item 5** (Config-5a…5d) already shipped the independent config-center **MVP** inside the modular monolith: `ConfigCenterPort`, namespaces, revision + ETag/If-Match, console namespace picker. Explicit MVP **non-goals** remain: history/rollback, push/watch, separate microservice, Nacos/Apollo replacement.

预发 Item 5 已交付配置中心 **MVP**（端口、命名空间、revision/ETag、控制台命名空间）。明确非目标仍是：历史/回滚、推送/watch、独立微服务、Nacos/Apollo 替代。

Owner ask after AuthZ widen push: **start 配置中心**. MVP is already DONE, so this ADR locks the **next deepen track** rather than re-doing 5a–5d.

推送后要求启动配置中心；MVP 已完，本 ADR 锁定**加深轨**，不重做 5a–5d。

## Decision / 决策

### D1 — Stay DB + HTTP behind `ConfigCenterPort` (no new process)

Deepen still lives in **platform-app + platform-contract**. “配置中心” remains a **port + shared tables + HTTP/console**, not a new deployable. Extract-to-service only behind the same port later (optional, new ADR).

加深仍在模块化单体：`ConfigCenterPort` + 共享表 + HTTP/控制台；**不**新起配置进程。

### D2 — First deepen = append-only **override history** (before push/watch)

Order locked:

1. **Config-6** — persist every successful override put as history `(namespace, key, revision, value, overridden_at)`; list history for operators.
2. **Config-7** — optional **restore** = write a new put from a history row (bumps revision; never mutate past rows).
3. **Config-8** — push/watch only if product still needs it after ETag poll; prefer long-poll/ETag over websocket first.

先历史，再显式恢复写入，最后才考虑推送；优先 ETag/长轮询而非 websocket。

### D3 — History is audit, not a second SoT

Current override row in `config_override` remains **source of truth** for effective value + revision. History is append-only evidence. Rollback is **not** silent rewind; it is an explicit new put (Config-7).

`config_override` 仍是生效值 SoT；历史只追加。回滚不是静默倒带，而是显式再 put（Config-7）。

### D4 — Non-goals (this deepen track)

- Nacos / Apollo / Spring Cloud Config Server product parity  
- Git-backed config, gray release, KMS vault product  
- Separate config microservice  
- Dumping full Spring `Environment`  
- Websocket fan-out in Config-6

## Consequences / 后果

- New Flyway table `config_override_history`; JDBC put appends one history row per new revision.  
- HTTP/console history list + restore UI land in later micro-slices (6b+ / 7a).  
- Item 5 plan stays the MVP record; this ADR + `docs/config/config-center-deepen-progress.md` own the deepen track.

新增历史表；put 追加行；列表/恢复 UI 后续切片。Item 5 计划仍记 MVP；本 ADR + 加深进度文管加深轨。

## Next / 下一步

**Config-6a** ✅: ADR + `V46` + JDBC/Memory append.  
**Config-6b** ✅: `GET /api/v1/config/{key}/history` + web console thin list (`?historyKey=`).  
**Config-7a** ✅: `POST …/history/{revision}/restore` + console restore (explicit put; no rewind).  
Next **Config-8** (optional): push/watch only if ETag poll is insufficient.
