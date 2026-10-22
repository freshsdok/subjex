# Questionnaire inventory / 问卷盘点（Q0）

Date: 2026-10-22 Asia/Shanghai. Parent ADR: [`../adr/0013-questionnaire-platform-capability.md`](../adr/0013-questionnaire-platform-capability.md).

## Existing Form surfaces (do not repurpose as 问卷)

| Surface | Path / type | Notes |
| --- | --- | --- |
| Form YAML catalog | `form-render` `*.form.yaml` + `FormCatalog` | Operator business declarations |
| Field list UI | `GET /forms`, console forms page | Field list, not designer |
| Submission store | table `form_submission` + `FormSubmissionStore` | Actor = operator identity; **not** survey SoT |
| Submit API | `FormSubmissionEndpoint` / forms API | AuthZ via PolicyEngine (AuthZ-1f) |
| Renderer | `FormRenderer`, `FieldKind` | Tech reuse OK; product merge **forbidden** |

## Planned questionnaire surfaces (ADR 0013)

| Surface | Planned | First slice |
| --- | --- | --- |
| Contract port | `QuestionnairePort` (+ stats / exportCsv) Memory/JDBC | **Q5 wired** |
| Tables | definition/item/response/answer (**V47**) + `questionnaire_collect_token` (**V48**) | **Q4** |
| HTTP | `/api/v1/questionnaires/**` (+ public collect under token path) | Q1–Q4 |
| Permissions | `questionnaire.read` / `questionnaire.write` | **Q1 seeded** (HTTP later) |
| Console | `/questionnaires` authoring + thin stats | Q6 |
| uni-app | fill / submit (+ anon token route) | Q7 |
| Manifest | `page.questionnaires`, write buttons | Q6 |

## Deliberate non-overlap

| Do | Do not |
| --- | --- |
| New tables + port | Store answers in `form_submission` |
| Console nav **问卷** / questionnaires | Rename `/forms` copy to 问卷 |
| uni-app respondent fill | Host C-end fill inside `web/` |
| Collect token for anon | Reuse operator session for public submit |

## Schema (Q1 applied — `V47__questionnaire.sql`)

```text
questionnaire (
  questionnaire_id PK,
  tenant_id NOT NULL,
  title NOT NULL,
  status NOT NULL,          -- DRAFT | PUBLISHED | CLOSED
  revision NOT NULL,        -- bumps on published edit
  collect_mode NOT NULL,    -- AUTH_END_USER | ANON_LINK (+ preview flag at API)
  created_at, updated_at
)

questionnaire_item (
  item_id PK,
  questionnaire_id NOT NULL,
  revision NOT NULL,        -- items versioned with definition revision
  sort_order NOT NULL,
  question_type NOT NULL,
  prompt NOT NULL,
  required NOT NULL,
  options_json NULL,        -- choices / rating max
  …

questionnaire_response (
  response_id PK,
  questionnaire_id NOT NULL,
  revision NOT NULL,
  tenant_id NOT NULL,
  respondent_realm NOT NULL, -- END_USER | OPERATOR | ANON
  respondent_subject_id NOT NULL,
  submitted_at NOT NULL,
  …

questionnaire_answer (
  response_id + item_id PK,
  value_json NOT NULL
)
```

Q1 created all four tables; Q3 writes response/answer (OPERATOR = preview).
