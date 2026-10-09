# Real AI + mandatory confirm / 真 AI 与强制确认写回（Item 3）

**Status:** **Item 3 DONE** (AI-3d 2026-10-09 Asia/Shanghai); next pre-alpha **item 5** (independent config center MVP) — Item 4 DONE at Scale-4d  
**Hard rule:** **NO AI write to business state without a consumed confirm ticket.** Preview-only by default; confirm + audit required for write-back.  
**Reuse:** Cap-* catalog, `CapabilityRunner` + `ModelCompletionClient`, console `/capabilities`, `AiWriteConfirmGate`, task `HumanConfirmation` precedent, `OperatorActionAudit`.  
**Non-goals (item 3):** free-form agents, multi-vendor SDKs in platform-app, live paid HTTP calls in CI, auto-write into PROMOTED entities/migrations.

---

## Slice plan / 切片计划

| Slice | Deliverable | Done when |
| --- | --- | --- |
| **3a** ✅ | Inventory + plan; `ModelCompletionClient` + `AiWriteConfirmGate` scaffold | Plan + unit tests |
| **3b** ✅ | Runner → client; `platform.ai.completion.mode=stub\|http`; preview-only | Stub default; AbsentTest green |
| **3c** ✅ | `write-ticket` / `write-back`; console confirm UX; audit preview/confirm | Fail-closed without ticket |
| **3d** ✅ | E2E `ai.summarizePreview` run→ticket→write-back once→reject; docs walkthrough | **Item 3 UX/API DONE** |

---

## Console walkthrough / 控制台步骤（`/capabilities`）

1. Sign in with an operator that has **`page.read`**.  
2. Open **算法 / AI 能力** (`/capabilities`). Note: default completion mode is **stub** (no API keys).  
3. Select **`ai.summarizePreview`**, enter `inputText`, click **试跑** → preview text only (audit `capability.ai.preview`).  
4. Click **签发写回确认票** → ticket id + expiry shown (still no DB write).  
5. Click **确认写回** → server **`consume`s** the ticket and runs the **noop** sink (`persisted=false`, suggestion = preview). Audit `capability.ai.confirm`.  
6. Confirming again with the same ticket → **400** (already used). Writing back with no/invalid ticket → **400**.

**Hard rule reminder:** Preview and ticket issue do **not** mutate business tables. Only `write-back` after a valid one-shot ticket may touch a sink; today’s sink is **noop** (draft suggestion in the response only).

---

## API (AI write path)

| Step | Method | Path | Notes |
| --- | --- | --- | --- |
| Preview | `POST` | `/api/v1/capabilities/{id}/run` | Any catalog id; AI audits preview |
| Issue ticket | `POST` | `/api/v1/capabilities/{id}/write-ticket` | **AI only**; body `{inputText}` |
| Write-back | `POST` | `/api/v1/capabilities/{id}/write-back` | Requires full ticket fields + `previewText`; `consume` then sink |

E2E coverage: `AiSummarizePreviewConfirmE2ETest` (preview → ticket → once → second fail).

---

## Secrets & modes / 密钥与模式

| Config / env | Default | Purpose |
| --- | --- | --- |
| `platform.ai.completion.mode` / `PLATFORM_AI_COMPLETION_MODE` | **`stub`** | `stub` = local template client; `http` = `HttpModelCompletionClient` |
| `platform.ai.completion.http.base-url` / `SUBJEX_AI_HTTP_BASE_URL` | empty | Gateway base URL (http mode) |
| `platform.ai.completion.http.api-key` / `SUBJEX_AI_HTTP_API_KEY` | empty | Bearer token — **never commit**; empty → fail-closed |
| `platform.ai.completion.http.model` / `SUBJEX_AI_HTTP_MODEL` | empty | Model id (http mode) |

**HTTP transport remains gated:** even with secrets set, this build **refuses live vendor I/O** (`transport not enabled`) so CI stays free of paid calls. Use **`mode=stub`** for local/CI green path. `platform-app` still has **no** Maven dependency on `model-gateway` (`ModelGatewayAbsentTest`).

---

## 3a–3d landed summary

1. Port + local stub + in-memory confirm gate (3a).  
2. Runner wired to client; optional http mode fail-closed (3b).  
3. Ticket/write-back APIs + console UX + audits (3c).  
4. E2E + this walkthrough; **Item 3 DONE** (3d).

## Next (pre-alpha item 4)

Horizontal scale MVP (**Item 4 DONE**): shared JDBC rate-limit + delivery breaker opt-in; gate in `docs/release/single-replica-gate.md`. Next: independent config center MVP.

---

## Cross-links

- Thin Cap: [`docs/lowcode-roadmap.md`](../lowcode-roadmap.md) § Thin algo/AI  
- Architecture: [`ARCHITECTURE.md`](../../ARCHITECTURE.md)  
- Single-replica / breaker gate: [`docs/release/single-replica-gate.md`](../release/single-replica-gate.md)  
- Console: `web/src/app/(console)/capabilities/`  
