# Controlled migration UX deepen / 受控迁移体验加深（Item 2）

**Status:** Item 2 UX **DONE** (2d landed 2026-10-09 Asia/Shanghai); next pre-alpha **item 3** (real AI + mandatory confirm)  
**Non-goal:** free / casual online DDL. Keep fail-closed whitelist apply + dual-track promote bind.  
**Reuse:** MQ-1…3 APIs/UI + RT-4 `DeclarationMigrationAutoEnqueueService`.

---

## Inventory / 现状盘点

| Layer | What exists |
| --- | --- |
| Backend auto-enqueue (RT-4) | Entity `PUT` → plan CREATE / ALTER ADD vs PROMOTED\|classpath\|empty → enqueue **PENDING**; idempotent; no auto-APPLY; dangerous changes 400 before draft insert. |
| Backend apply (MQ-2) | `POST …/migrations/{id}/review` then `…/apply`; fail-closed single-statement DDL; promote blocked until settled. |
| Console queue (MQ-3) | `/declarations` entity panel: **Refresh** list; **manual** SQL enqueue (review→confirm); per-row **Review** / **Apply** (each with confirm step); status + SQL + error columns. |
| Wizards | Business-table / entity wizards save drafts via same PUT (entity triggers RT-4). Path hint text: 新建业务表 → migration → promote → pages. |
| Docs | `docs/declaration-migration.md` § RT-4; `docs/lowcode-roadmap.md` MQ + RT-4. |

### Click path today / 今天要点几次

1. Save entity draft (or 新建业务表) → **backend** auto-enqueues PENDING (silent).  
2. Operator must **Refresh migrations** (or navigate away/back) to see PENDING — **gap**.  
3. Per PENDING row: Review → confirm → Apply → confirm (two confirm dialogs; intentional fail-closed).  
4. Promote still separate; blocked if open migrations.

**2a tiny win (landed):** after successful entity draft save, console calls `loadMigrations()` so PENDING rows appear without a manual Refresh.

**2b (landed):** `loadMigrations(key?, kind?)` with explicit args; auto-load after `openKey` (entity) and after 新建业务表 (`createBusinessTableDrafts` → `openKey`); entity PUT returns `enqueuedMigrationIds`; console toast 「已入队 N 条 PENDING」; manual SQL enqueue under Advanced disclosure. **Does not** collapse Review+Apply (that is 2c / G3).

---

## Gaps vs「草稿保存 → 自动入队 → 一键审阅」

| Gap | Severity | Notes |
| --- | --- | --- |
| G1. Save does not surface auto-enqueue result | **Done (2b)** | PUT `RevisionDocument.enqueuedMigrationIds`; console toast + saved notice. |
| G2. Open entity / 新建业务表 does not auto-load queue | **Done (2b)** | `loadMigrations(key, kind)` from `openKey`; business-table success via `openKey`. |
| G3. No “review+apply” one-click for a single PENDING | **Done (2c)** | Guided 「审阅并执行」: one confirm with SQL, then client review→apply; discrete Review/Apply kept. |
| G4. Manual SQL enqueue still primary visual | **Done (2b)** | Manual enqueue tucked under Advanced show/hide. |
| G5. FAILED apply feedback | **Done (2c)** | FAILED row highlight + hint; 「填入高级入队」 re-queue path; REVIEWED still uses Apply. |
| G6. Cancel PENDING/REVIEWED from console | **Done (2c)** | POST `…/migrations/{id}/cancel` + console confirm cancel. |
| G7. Audit trail UX | **Done (2d)** | Filtered `GET /api/v1/audit` for `declaration.migrate.*` under migration panel; link to `/audit`. |
| G8. Business-table wizard post-create | **Done (2b)** | Queue auto-loads after wizard openKey; enqueue toast on entity PUT. |
| G9. PUT response schema | **Done (2b)** | `enqueuedMigrationIds` on entity draft PUT. |

---

## Slice plan / 切片计划

| Slice | Deliverable | Done when |
| --- | --- | --- |
| **2a** (this) | Inventory + this plan; tiny win: refresh migrations after entity save | Plan committed; save→list refresh |
| **2b** ✅ | Post-save / open feedback: `loadMigrations(key?, kind?)`; open entity + business-table auto-load; PUT `enqueuedMigrationIds` + toast; manual SQL under Advanced | Operator sees PENDING without hunting Refresh |
| **2c** ✅ | Guided **审阅并执行** (SQL confirm → review+apply); cancel PENDING/REVIEWED; FAILED emphasis + Advanced re-queue; discrete Review/Apply kept | Fewer clicks; still fail-closed |
| **2d** ✅ | Path checklist chip; migrate audit list (`declaration.migrate.*`); notices/phrases polish; docs | **Item 2 UX DONE** |

**Out of scope for item 2:** DROP/RENAME UI, auto-APPLY on save, free SQL without review, multi-statement scripts.

---

## 2b landed / 已落地

1. `loadMigrations(key?, kind?)` — explicit args avoid stale `selectedKey` after `openKey`.  
2. Auto-load queue after open entity + after 新建业务表 success.  
3. Entity draft PUT → `RevisionDocument.enqueuedMigrationIds` + console toast 「已入队 N 条 PENDING」.  
4. Manual SQL enqueue under Advanced disclosure.  
5. Review+Apply remain separate confirms (G3 → **2c**).

## 2c landed / 已落地

1. Guided 「审阅并执行」 — one confirm showing SQL, then POST review → POST apply (no combined unsafe API; confirm required).  
2. Cancel PENDING/REVIEWED via `POST …/migrations/{id}/cancel` (`markCancelled`).  
3. FAILED row highlight + hint; 「填入高级入队」; Apply remains for REVIEWED.  
4. Discrete Review / Apply kept (fail-closed).

## 2d landed / 已落地 — Item 2 DONE

1. Path checklist chip: draft → PENDING → REVIEWED → APPLIED → promote.  
2. Migration audit panel: `GET /api/v1/audit` filtered to `declaration.migrate.*` for open entity; forbidden → hint + `/audit` link.  
3. AuditPage plain words for migrate enqueue/review/apply/cancel.  
4. Hint/phrases polish for path + guided confirm.

## Next (pre-alpha item 3)

Real AI via model-gateway + **mandatory human confirm** before critical writes (stubs already exist: `ai.summarizePreview` / `ai.suggestTitlePreview`).

---

## Cross-links

- MQ / RT-4: [`docs/declaration-migration.md`](../declaration-migration.md), [`docs/lowcode-roadmap.md`](../lowcode-roadmap.md)  
- AuthZ item 1 done: [`docs/authz/cedar-or-casbin-adr.md`](../authz/cedar-or-casbin-adr.md)  
- Console: `web/src/app/(console)/declarations/declarations-console.tsx`  
- Auto-enqueue: `DeclarationMigrationAutoEnqueueService` + `DeclarationDraftEndpoint`
