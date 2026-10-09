# ADR — AuthZ engine: Cedar (not Casbin) behind `PolicyEngine`

**Status:** **Accepted / Implemented (1a–1d)** 2026-10-09 Asia/Shanghai — default `platform.authz.engine=cedar`; SQL selectable  
**Context:** Post-O8 pre-alpha track item **1** — integrate a real policy engine via the existing `PolicyEngine` port before `v0.1.0-alpha.1`.  
**Decision:** **Cedar** (`com.cedarpolicy:cedar-java`, Apache-2.0). Casbin remains a documented non-choice for this cut.

---

## Inventory (current wiring) / 现状盘点

| Piece | Location | Role |
| --- | --- | --- |
| `PolicyEngine` | `platform-app/.../security/PolicyEngine.java` | Port: `evaluate` / `require` → `AccessDecision`. |
| `SqlRbacPolicyEngine` | `.../SqlRbacPolicyEngine.java` | First adapter: bridges `PolicyPrincipal` → lightweight `OperatorPrincipal`; tenant mismatch; delegates to `AccessChecker`. |
| `AccessChecker` | `.../AccessChecker.java` | Permission membership + optional tenant grant + optional `OrganizationScope` vs resource org id; deny reasons for explainable 403. |
| `AccessDecision` | `.../AccessDecision.java` | Explainable result (subject, tenant, orgScope, resource, action, matchedPermission, allowed, denyReason). |
| `OrganizationScope` | `.../OrganizationScope.java` + `OrganizationScopeResolver` | Formal scope from Membership + CONTAINS (O8); Relationship ≠ Authorization. |
| Wiring | `PlatformWiring.policyEngine(...)` | `PolicyEngineFactory` from `platform.authz.engine` (default **cedar**). |
| Callers (port) | `org.legacy.OrgApiEndpoint` membership writes; tests (`SqlRbacPolicyEngineTest`, org security suites, `OrgAuthorizationGatesTest`) | Most declaration paths still call `AccessChecker` / `DeclarationAccess` directly — migration must widen port use (1c/1d). |
| Docs | `ARCHITECTURE.md` §18; `docs/operator-permissions.md` (AX-3 table); `docs/auth-roadmap.md`; O1 ADR non-goals | “Cedar/Casbin subset port; jars later; no self-authored DSL; no Zanzibar.” |

**Baseline behavior to preserve:** fail-closed on blank permission, missing permission, tenant missing/ungranted/mismatch, org scope missing / out of scope; structured 403 fields; no new role names for org scope.

---

## Options considered / 备选

### A — Cedar (chosen)

- **Shape fit:** Matches the port’s primary mapping (principal / action / resource / context). `PolicyContext` + `OrganizationScope` become Cedar **context** attributes; resource org id stays on the resource entity — no need to invent Casbin `g` roles for hierarchy.
- **Explainability:** Cedar authorize diagnostics align with AX-1 `AccessDecision` / denyReason (map engine diagnostics → existing deny codes where possible; keep codes stable for API clients).
- **Org scope:** ABAC-style attributes + when-clauses express SELF / SELF_AND_DESCENDANTS / EXPLICIT without new platform roles (AUTH-01 / O8 locked).
- **Java / Spring:** Official `cedar-java` on Maven Central (Apache-2.0); prefer **uber** artifact (bundles FFI native). JDK 17+ (repo is 21). Spring remains a thin adapter bean behind `PolicyEngine`.
- **License:** Apache-2.0 — same as subjex.
- **Cost / risk:** Native FFI packaging (linux amd64/arm64 in Docker multi-stage); policy authoring UX later; must dual-run vs SQL RBAC before cutover.

### B — Casbin / jCasbin (rejected for this track)

- **Pros:** Pure JVM, common Spring samples; `(sub, obj, act)` maps cleanly to `requiredPermission` as `act`.
- **Cons:** Enforce is primarily boolean; weaker first-class diagnostics vs Cedar for our explainable 403 story. Tenant “domain” + org hierarchy often push **role/`g` graphs**, fighting “no new role names / org ≠ permission”. Would bend the port toward Casbin-first naming already marked secondary in docs.
- **License:** Apache-2.0 (tie) — not decisive.

### C — Self-authored AuthZ DSL (forbidden)

Already a pre-alpha **don’t-do**. This ADR does **not** add a house language; Cedar *is* the policy language.

---

## Decision / 决定

**Use Cedar** as the only planned production engine behind `PolicyEngine`.  
Keep `SqlRbacPolicyEngine` until dual-run gates pass, then cut over wiring (default bean → Cedar adapter). Do **not** add Casbin Maven deps in this track unless a future ADR reverses this (unlikely before alpha).

**中文摘要：** 在已有 `PolicyEngine` 端口后接入 **Cedar**；不接 Casbin；禁止自研 DSL。组织范围继续用上下文属性表达，不新增角色名。SQL RBAC 适配器保留至双跑门禁通过后再切默认实现。

---

## Non-goals / 明确不做（本项与后续 1b–1d）

- Self-authored policy DSL or in-house interpreter.
- Zanzibar / ReBAC graph store.
- Online permission/role editor (operators still SQL/declarations).
- Replacing named platform permissions (`page.read`, …) with free-form strings in v1 policies — map **existing** permission names to Cedar actions.
- Changing AUTH-01 (relationship ≠ authorization) or reintroducing legacy `org_unit` scope tables.
- Casbin dual-engine product support.

---

## Migration plan from `SqlRbacPolicyEngine` / 迁移计划

1. **Parity policies:** Express current AccessChecker rules as Cedar policies (permit when principal has action permission; tenant grant checks may remain host-side helpers or Cedar when-clauses — decide in 1b; prefer host-side tenant grant + Cedar for permission+org to limit blast radius).
2. **Adapter:** `CedarPolicyEngine implements PolicyEngine` — build Cedar request from `PolicyPrincipal` / `AccessAction` / `PolicyResource` / `PolicyContext`; map allow/deny (+ diagnostics) → `AccessDecision` with **stable denyReason codes**.
3. **Dual-run:** For selected paths, evaluate SQL and Cedar; log/metric on mismatch; fail CI gate if mismatch rate ≠ 0 on golden suite.
4. **Widen callers:** Move declaration/form/entity paths from raw `AccessChecker` to `PolicyEngine` so cutover is one bean flip.
5. **Cutover:** `PlatformWiring` returns Cedar adapter; keep SQL engine for tests / emergency `platform.authz.engine=sql` flag (optional, 1d).
6. **Docs:** Update `operator-permissions.md` AX-3 (“Cedar jars landed”); SECURITY known-limits if packaging notes needed.

---

## Slice plan / 切片计划（Item 1）

| Slice | Deliverable | Done when |
| --- | --- | --- |
| **1a** (this) | Engine choice + inventory + this ADR + PROGRESS lock | ADR accepted; no runtime change |
| **1b** | **Done:** `platform-app` + `com.cedarpolicy:cedar-java:4.10.0:uber` (no separate module — `PolicyEngine` lives in app); `CedarPolicyEngine` + `authz/baseline.cedar` (allow + forbid_anonymous); tests green; SQL still default. FFI fail-soft → `cedar_unavailable`. | Tests green; SQL still default |
| **1c** | **Done:** `PolicyEngineDualRunGatesTest` — 12 golden cases (allow/deny permission, blank, tenant mismatch/grant, org in/out/missing); assert `allowed` + `denyReason` (+ `matchedPermission` on allow). Intentional divergence: `Subject::"anonymous"` forbid in Cedar vs SQL allow — documented, not in golden set. | Mismatch → test failure |
| **1d** | **Done:** default `cedar` via `platform.authz.engine` / `PLATFORM_AUTHZ_ENGINE`; `sql` fallback; fail-closed if cedar selected but FFI down; removed `forbid_anonymous`; null subject → `__unauthenticated__`. Residuals: many paths still call `AccessChecker`/`DeclarationAccess` directly. | Focused auth suite green; item 1 done |

**1b landed files:**

- `platform-app/.../security/CedarPolicyEngine.java`
- `platform-app/pom.xml` — `com.cedarpolicy:cedar-java:4.10.0` classifier `uber`
- `platform-app/src/main/resources/authz/baseline.cedar`
- `platform-app/src/test/java/.../security/CedarPolicyEngineTest.java`
- Deny reasons: `AccessDecision.DENY_CEDAR_UNAVAILABLE` / `DENY_CEDAR_ERROR`

**1c landed:** `platform-app/src/test/.../PolicyEngineDualRunGatesTest.java` (12 golden + divergence doc test).

**Intentional divergences (not golden):**
- Principal subjectId `anonymous` — Cedar `forbid_anonymous` in `baseline.cedar` denies; SQL RBAC allows if permission held. Do not use `anonymous` as a real subject id; 1d may rename the forbid sentinel.
- Null/blank subjectId still maps to Cedar `Subject::"anonymous"` for entity UID; golden uses `null` principal (empty perms) so both deny `permission_missing`.

**1d landed:** `PolicyEngineFactory` + `PlatformWiring` + `application.yml` `platform.authz.engine` (default cedar).

**Anonymous / unauthenticated:** removed Cedar `forbid_anonymous`. Subject id `anonymous` is a normal principal (SQL parity). Null/blank `subjectId` → Cedar EntityUID `Subject::__unauthenticated__` (empty permissions → deny).

**Residuals (post–item 1):** declaration/form/entity paths still largely call `AccessChecker` / `DeclarationAccess` directly — widen to `PolicyEngine` later. CI/Docker must ship `cedar-java` uber natives (or set `PLATFORM_AUTHZ_ENGINE=sql`).

**Next track item:** **2** controlled migration UX deepen.

---

## Consequences / 后果

- Alpha AuthZ story becomes “SQL RBAC baseline + Cedar swap-in path”, not “subset shape forever”.
- Docker/CI must validate native lib load on the runner OS/arch (call out in 1b).
- Casbin samples in community docs stay irrelevant; avoid half-integrating both.

---

## References

- `PolicyEngine.java`, `SqlRbacPolicyEngine.java`, `AccessChecker.java`, `AccessDecision.java`
- `docs/operator-permissions.md` § Policy engine port (AX-3)
- `ARCHITECTURE.md` §18 Context AuthZ
- Cedar Java: https://github.com/cedar-policy/cedar-java (Apache-2.0)
