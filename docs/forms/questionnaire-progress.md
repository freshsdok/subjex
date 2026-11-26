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
| Q0–Q5 | **done** (pushed `718000f`) | ADR → CRUD → lifecycle → auth → anon → stats/CSV |
| Q6a/b | **done** (local `6a7d7bc`) | REST + console `/questionnaires` + Manifest |
| **Q7** | **done** (local) | uni-app list hub + token fill (ANON_LINK); Manifest nav/page/button |
| Assignment / ACL | **done** (later product-deepening) | Operators assign AUTH_END_USER questionnaires to a person or organization; unassigned published ones are not visible to signed-in users. ANON_LINK tokens stay unconstrained. |

## Q7 delivered

1. **Pages:** `pages/questionnaire/list` (token entry + optional auth published list); `pages/questionnaire/fill?token=` (public load/submit).  
2. **API client:** `clients/uni-app/src/api/questionnaire.ts` — `publicGetByToken` / `publicSubmit` / `listAuthenticated` + clientKey storage.  
3. **Manifest:** uni-app `nav.questionnaires` (login-only), `page.questionnaires.fill`, `btn.questionnaires.submit` (entries 5→8).  
4. **Out of thin slice:** AUTH_END_USER authenticated submit HTTP; rich multi-item authoring on mobile; CAPTCHA.

## Pointers

- Form `/forms` unchanged
- Console operator surface remains `web/` `/questionnaires`
