# Questionnaire capability progress / 问卷平台能力进度

- ADR: [`docs/adr/0013-questionnaire-platform-capability.md`](../adr/0013-questionnaire-platform-capability.md) (**Accepted**)
- Inventory: [`questionnaire-inventory.md`](questionnaire-inventory.md)
- Date: 2026-10-22 Asia/Shanghai; current status 2026-11-28 (tip `7c1d7d7`)

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

## Current status / 现行状态（2026-11-28）

Later slices did not change the product meaning of stats (still production responses only; choice/rating buckets; operator preview excluded; no funnel). Production `JdbcQuestionnairePort.stats()` now counts and groups in SQL, then buckets compact grouped rows in Java (`7c1d7d7`); it does not load every production response and answer into memory. The Memory/test port may still aggregate in memory. Operator questionnaire CSV (and tenant ZIP) stream in-request in keyset pages (`e974759`); `exportCsv()` remains a leftover String helper for tests. AUTH_END_USER assignment is landed, as in the table above.

后续切片未改变汇总的产品含义（仍只计生产答卷、选择/评分桶、排除预览，不是漏斗）。生产 JDBC `stats()` 已改为库内计数与分组后再在 Java 中分桶（`7c1d7d7`），不再把每一份生产答卷与答案装入内存。Memory 测试端口仍可在内存中汇总。操作员问卷 CSV 与租户 ZIP 已改为请求内按键集流式写出（`e974759`）；`exportCsv()` 仍是测试用字符串辅助。认证问卷分配已落地，见上表。
