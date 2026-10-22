# Questionnaire capability progress / 问卷平台能力进度

- ADR: [`docs/adr/0013-questionnaire-platform-capability.md`](../adr/0013-questionnaire-platform-capability.md) (**Accepted**)
- Inventory: [`questionnaire-inventory.md`](questionnaire-inventory.md)
- Date: 2026-10-22 Asia/Shanghai

## Lock / 锁定

| Item | Stance |
| --- | --- |
| Form vs 问卷 | Form stays Form (ADR 0011 §D2/§D3) |
| Operator preview | Excluded from production stats **and** CSV |
| Anon collect | ANON_LINK token + RateLimitPort |

## Slices / 切片

| Slice | Status | Notes |
| --- | --- | --- |
| Q0–Q4 | **done** (local) | ADR → CRUD → lifecycle → auth → anon token |
| **Q5** | **done** (local) | Thin stats + CSV (production only); no BI / no HTTP |
| Q6 | next | Console UI + Manifest + HTTP |
| Q7 | later | uni-app fill |

## Q5 delivered

1. Types: `QuestionnaireStats` / `QuestionnaireItemStats` + `QuestionnaireStatsSupport` (aggregate + CSV).  
2. Port: `stats` / `exportCsv` on Memory + JDBC.  
3. **Stats shape:** `responseCount` + per-item counts for SINGLE/MULTI/RATING only (options seeded at 0; TEXT skipped).  
4. **CSV columns:** `response_id,revision,respondent_realm,respondent_subject_id,submitted_at` + one column per item id (definition order).  
5. OPERATOR preview excluded. Tests: StatsSupport 1 + Memory 7 + JDBC 14.

本地提交；未推送。Next = **Q6** console UI + Manifest + HTTP.

## Pointers

- Form `/forms` unchanged
