# ADR 0011 — No standalone questionnaire product (near-term) / 近端不做独立问卷产品

- Status: **Accepted**（2026-10-22 Asia/Shanghai；autonomous order #4 — educated default lock）
- Date: 2026-10-22 (Asia/Shanghai)
- Relates: ADR 0007 (client triad)、低代码 Form / `docs/form-ui.md`、`docs/node6-progress.md`、ADR 0010（UI Manifest）、[`docs/forms/questionnaire-progress.md`](../forms/questionnaire-progress.md)

## Context / 背景

Operators already have a **low-code Form** path: classpath `*.form.yaml`, catalog + submission store, console `/forms`, optional hybrid entity upsert. Product language sometimes conflates that with **问卷 / survey / quiz** (public or anon collect, typed question banks, stats dashboards, export packs, a distinct subject model).

操作员侧已有低代码 **Form**（YAML 目录、提交存档、控制台 `/forms`）。产品口语里常把 Form 与 **问卷/survey/quiz**（公开/匿名采集、题型库、统计看板、导出、独立主体模型）混为一谈。

Ask for this release / near-term: lock an educated default — **do not** ship a standalone questionnaire product; keep Form as business entry; require a **new ADR** before any questionnaire build.

本发布/近端诉求：锁定默认——**不做**独立问卷产品；Form 仍作业务录入；问卷须另开 ADR。

## Decision / 决策

### D1 — Near-term: NO standalone questionnaire

**This release and the near-term roadmap do not include** a standalone questionnaire / survey / quiz product (no new module, console nav item named 问卷, public collect surface, or quiz runtime).

**本发布与近端路线不包含**独立问卷/survey/quiz 产品（无新模块、无「问卷」导航、无公开采集面、无测验运行时）。

### D2 — Operator Form stays Form (not renamed to 问卷)

Keep the existing operator low-code **Form** as the business data-entry surface:

| Keep | Do not |
| --- | --- |
| Console `/forms`, `FormCatalog`, `form_submission` | Rename Form → 问卷 in UI copy or nav |
| `*.form.yaml` + FormRenderer field list | Treat Form submissions as survey responses by default |
| Per-form write permissions (e.g. `registry.write` / `config.write`) | Add public/anon Form submit without AuthZ review |

操作员低代码 **Form** 继续作业务录入入口；**不**把 Form 改名为问卷；**不**默认把提交当调研答卷。

### D3 — Form vs 问卷 boundary

| Dimension | **Form（in scope）** | **问卷 / survey / quiz（out until new ADR）** |
| --- | --- | --- |
| Audience | Authenticated **operators** (console) filling business records | End-users / public / anon respondents |
| Purpose | Structured **business entry** tied to declarations / config / registry | Opinion/measure collect, quiz scoring, research |
| Schema | Low-code field list (`*.form.yaml`) | Question types (single/multi/matrix/nps/…), branching, banks |
| Identity | Operator principal + tenant | Often anon or lightweight subject; may need new subject model |
| Analytics | Submission history / audit | Stats dashboards, funnels, export packs |
| Clients | Primarily `web/` console | Would need uni-app / public pages (ADR 0007) |

**Rule of thumb:** if the primary consumer is an **operator recording business data**, it is Form. If the primary consumer is a **respondent answering questions for aggregation**, it is 问卷 — and needs a new ADR.

经验法则：主消费者是**操作员录业务数据** → Form；主消费者是**答卷人供汇总** → 问卷，须新 ADR。

### D4 — Future questionnaire requires a new ADR

Any questionnaire / survey / quiz work **must** open a **new ADR** covering at least:

1. Question types + branching
2. Public / anonymous collect + abuse controls
3. Stats + export
4. Subject / identity model (vs operator / END_USER inbox subject)
5. Client placement (console vs uni-app vs public) per ADR 0007

Future ADR may **reuse** FormRenderer / submission storage patterns where fit — reuse is not automatic product merge.

未来问卷须新 ADR；可复用 Form 技术切片，但不得默认合并成同一产品。

## Non-goals / 明确不做（本 ADR）

- Building survey/quiz UX in this wave
- Renaming `/forms` or Form copy to 问卷
- Public anonymous collect endpoints
- Stats / export product for “responses”
- Treating IM / inbox as a questionnaire channel

## Consequences / 后果

- Roadmap and sales language: say **Form** for operator entry; say **问卷 not in near-term** unless a follow-up ADR is Accepted.
- Contributors: reject PRs that add 问卷 nav, anon collect, or quiz scoring **without** that ADR.
- Progress pointer: [`docs/forms/questionnaire-progress.md`](../forms/questionnaire-progress.md)

## References

- Form UI notes: `docs/form-ui.md`
- Form catalog wave: `docs/node6-progress.md`
- Client triad: ADR 0007
