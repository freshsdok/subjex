# ADR 0013 — Questionnaire as platform capability / 问卷作为平台基础能力

- Status: **Accepted**（2026-10-22 Asia/Shanghai；用户「开一条问卷调查的线…作为架构基础能力的一个组成部分」）
- Date: 2026-10-22 (Asia/Shanghai)
- Relates: ADR 0011（近端不做独立问卷 — **本 ADR 修订其 D1**；**保留** D2/D3 Form 边界）、ADR 0007（client triad）、ADR 0010（UI Manifest）、低代码 Form / `docs/form-ui.md`、[`docs/forms/questionnaire-progress.md`](../forms/questionnaire-progress.md)
- Amends: ADR 0011 §D1（近端 NO → 现按本 ADR 开工）；**does not** rename Form or merge products

## Context / 背景

ADR 0011 locked an educated default: **no** standalone questionnaire in the near-term roadmap, keep operator **Form** as business entry, and require a **new ADR** before any survey/quiz build (covering types, public/anon, stats, export, subject, clients).

ADR 0011 曾锁定：近端不做独立问卷；Form 仍作业务录入；问卷须新 ADR。

Product ask (2026-10-22): open a **questionnaire track as architecture foundation capability** — not a Form rename, not a one-off business feature. This ADR **is** the follow-up ADR required by 0011 §D4.

产品诉求：把**问卷调查**作为**架构基础能力**开线（非 Form 改名、非一次性业务页）。本 ADR 即 0011 §D4 所要求的后续 ADR。

Inventory (tip ~`fb34443`):

| Surface | Exists? | Role vs questionnaire |
| --- | --- | --- |
| `FormCatalog` / `*.form.yaml` / `/forms` | Yes | Operator **business entry** — keep; do **not** rename to 问卷 |
| `form_submission` | Yes | Accepted Form rows (operator actor) — **not** survey SoT |
| `FormRenderer` / field kinds | Yes | Reusable **tech** patterns only |
| Console `web/` | Yes | Authoring + stats home (operators) |
| `clients/uni-app/` | Shell + platform pages | Respondent fill (H5 + 微信小程序) per ADR 0007 |
| Standalone 问卷 module / tables | **No** | This track |

盘点：Form 与提交已有，但是操作员业务录入；问卷表/端口尚无；控制台与 uni-app 分属操作员面与答卷面。

## Decision / 决策

### D1 — Open questionnaire as a **platform capability** (amends 0011 D1)

**Ship** a first-class questionnaire / survey capability inside the modular monolith:

- Contract port in `platform-contract` (`QuestionnairePort` + types)
- Shared JDBC tables via Flyway (dual MySQL/PostgreSQL)
- HTTP under `/api/v1/questionnaires/**` (exact paths in slices)
- Console authoring/stats + uni-app respondent fill

This **supersedes ADR 0011 §D1** (“near-term NO”). ADR 0011 §D2 / §D3 (**Form stays Form**; Form vs 问卷 boundary) **remain in force**.

开工平台级问卷能力；**取代** 0011 §D1 的近端不做；**保留** Form 不改名与边界表。

### D2 — Form and 问卷 stay distinct products

| Rule | Stance |
| --- | --- |
| Rename Form → 问卷 / merge nav | **Forbidden** |
| Reuse `form_submission` as survey SoT | **Forbidden** |
| Reuse FormRenderer / validation / YAML habits | **Allowed** as tech reuse |
| Operator business entry | Continues on `/forms` |
| Respondent aggregation collect | Lives on questionnaire APIs + `/questionnaires` console + uni-app fill |

经验法则仍按 0011 §D3：主消费者是操作员录业务 → Form；主消费者是答卷人供汇总 → 问卷。

### D3 — Domain model (MVP)

```text
questionnaire              (definition header + lifecycle)
        │ 1
        ▼ N
questionnaire_item         (ordered questions)
        
questionnaire_response     (one submit session)
        │ 1
        ▼ N
questionnaire_answer       (per-item answers)
```

| Concept | MVP? | Notes |
| --- | --- | --- |
| **Questionnaire** | Yes | `questionnaire_id`, `tenant_id`, `title`, `status`, `revision`, collect mode, timestamps |
| **Item** | Yes | `item_id`, type, prompt, required, options_json, sort_order |
| **Response** | Yes | `response_id`, questionnaire + revision, respondent realm/subject, submitted_at |
| **Answer** | Yes | `response_id` + `item_id`, value_json |
| **Branching / skip logic** | **No** (MVP linear) | Schema may later add `rules_json`; no engine this wave |
| **Quiz scoring / exam** | **No** | Survey collect first; scoring needs deepen ADR/slice |
| **Question bank library** | **No** | Per-questionnaire items only |

Lifecycle: `DRAFT` → `PUBLISHED` → `CLOSED` (no silent edit of published item text without bumping `revision`; answers bind to revision).

生命周期：草稿 → 已发布 → 已关闭；已发布改题须升 revision；答卷绑定 revision。

### D4 — Question types (MVP locked)

| Type | MVP | Notes |
| --- | --- | --- |
| `SINGLE_CHOICE` | Yes | options list |
| `MULTI_CHOICE` | Yes | options list |
| `TEXT_SHORT` | Yes | single-line |
| `TEXT_LONG` | Yes | multi-line |
| `RATING` | Yes | integer 1..N (N ≤ 10) |
| `NPS` / `MATRIX` / `DATE` / `FILE` | Later | deepen slices; not MVP |
| Branching | Later | linear only in MVP |

### D5 — Collect modes + subject / identity

| Mode | Audience | Auth | Abuse controls (minimum) |
| --- | --- | --- | --- |
| `AUTH_END_USER` | Logged-in end users | Bearer / OIDC (ADR 0007); **not** operator Redis session | Tenant fail-closed; one active response policy TBD per slice |
| `AUTH_OPERATOR_PREVIEW` | Operators previewing | Console BFF → Bearer + `questionnaire.write` / read | Never counts as production stats by default |
| `ANON_LINK` | Public / anonymous | Opaque **collect token** on publish (not operator session) | Rate limit + token revoke; CAPTCHA **later** if abuse |

Respondent model on every response:

- `respondent_realm`: `END_USER` \| `OPERATOR` \| `ANON`
- `respondent_subject_id`: identity id, or opaque anon id derived from token+response (not inbox subject reuse)

Do **not** treat IM / inbox as a questionnaire channel (0011 non-goal kept).

答卷主体分领域；匿名走采集令牌；**不**把站内信当问卷通道。

### D6 — Stats + export (thin, not BI)

MVP:

1. **Aggregates:** response count; per `SINGLE_CHOICE` / `MULTI_CHOICE` / `RATING` option or bucket counts  
2. **Export:** CSV of responses (+ answer columns) for operators with `questionnaire.read`  
3. **UI:** thin console panel — not a dashboard product, not third-party BI

Defer: funnels, cohort, PDF packs, realtime websocket stats.

### D7 — Clients (ADR 0007)

| Surface | Role |
| --- | --- |
| `web/` console | Authoring (create/edit/publish/close), response list, aggregates, CSV export |
| `clients/uni-app/` | Respondent **fill + submit** (H5 + 微信小程序); anon fill via token route on H5 |
| Public pages inside `web/` | **Forbidden** for C-end fill |

Manifest: add `page.questionnaires` (+ write buttons) when console page lands (ADR 0010).

### D8 — Placement / 落点

- Stay in **modular monolith**: `platform-contract` port + `platform-app` JDBC/HTTP/console wiring  
- **No** new deployable / microservice in this track  
- Next free Flyway after `V46` (e.g. `V47__questionnaire*.sql`) when Q1 lands  
- Permissions sketch: `questionnaire.read` / `questionnaire.write` (exact Cedar/matrix in AuthZ slice)

### D9 — Non-goals / 明确不做（本 ADR / MVP）

- Renaming `/forms` or Form copy to 问卷  
- Merging `form_submission` with questionnaire responses  
- Drag-and-drop Typeform-class designer in MVP (declare via API/JSON first; YAML optional later)  
- Quiz scoring, certificates, proctoring  
- Branching engine, matrix/NPS/file types in MVP  
- Using inbox/outbox as the collect channel  
- Training-dialogue / domain training product (stays out of subjex)

## Consequences / 后果

- Roadmap language: **Form** = operator business entry; **问卷** = platform survey capability under this ADR.  
- Contributors: PRs that rename Form or store survey answers in `form_submission` are rejected.  
- Progress: [`docs/forms/questionnaire-progress.md`](../forms/questionnaire-progress.md); inventory: [`docs/forms/questionnaire-inventory.md`](../forms/questionnaire-inventory.md).  
- ADR 0011 remains historical + boundary authority for Form; its §D1 near-term ban is amended here.

路线语言：Form ≠ 问卷；进度与盘点见 docs/forms；0011 仍管 Form 边界，D1 近端禁令由本 ADR 修订。

## Next / 下一步（切片）

| Slice | Intent |
| --- | --- |
| **Q0** | ✅ ADR + progress + inventory + stub types |
| **Q1** | ✅ Flyway `V47` + JDBC/Memory + create/get/list |
| **Q2** | ✅ Items replace + publish/close + revision bump |
| **Q3** | ✅ Auth submit + list (preview excluded from production) |
| **Q4** | ✅ ANON_LINK collect token + RateLimitPort |
| **Q5** | ✅ Thin aggregates + CSV (production only) |
| **Q6** | Console thin authoring / stats UI + Manifest |
| **Q7** | uni-app fill page (H5 first; 小程序 same project) |

## References

- ADR 0011: `docs/adr/0011-no-standalone-questionnaire.md`
- Client triad: ADR 0007
- Form UI: `docs/form-ui.md`
