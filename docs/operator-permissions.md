# Operators, permissions, and audit / 操作员、权限与审计

## Tables / 表

Flyway `V2__operator_permission.sql` adds the catalog. It writes no person and no password.
Flyway `V6__operator_management.sql` adds `operator.manage` and `operator_tenant_grant`.
Flyway `V7__tenant_manage.sql` adds `tenant.manage`. Flyway `V13__org_unit.sql` adds `org.read` and reserves role `platform.super-admin` (no ordinary grants). Flyway `V18__org_write.sql` adds `org.write` (granted to `platform-operator` only).

Flyway `V2__operator_permission.sql` 加入目录，不写入任何人，也不写入任何口令。
`V6__operator_management.sql` 增加 `operator.manage` 与 `operator_tenant_grant`。
`V7__tenant_manage.sql` 增加 `tenant.manage`。`V13__org_unit.sql` 增加 `org.read`，并预留角色 `platform.super-admin`（无普通授权）。`V18__org_write.sql` 增加 `org.write`（仅授予 `platform-operator`）。

| Table / 表 | One row is / 一行是 |
| --- | --- |
| `platform_permission` | one named thing an operator may do / 操作员能做的一件具名的事 |
| `platform_role` | a named bundle of permissions / 一组具名权限 |
| `role_permission` | this role grants this permission / 这个角色授予这项权限 |
| `subject_role` | this subject holds this role / 这个主体持有这个角色 |
| `operator_credential` | the password hash an account signs in with / 账号登录用的口令摘要 |
| `operator_tenant_grant` | this subject may act for this tenant id (`*` = all) / 该主体可代表该租户（`*` 为全部） |

An operator is an `account` → `subject_identity` in the reserved tenant `platform` → `subject`. The account must be `ACTIVE` and the identity `ACTIVE`. Audit entries name that identity.

操作员是 `account` → 保留租户 `platform` 里的 `subject_identity` → `subject`。账号和身份都必须是 `ACTIVE`。审计条目记的就是这个身份。

## Permissions / 权限

| Permission / 权限 | Paths / 路径 |
| --- | --- |
| `admin.read` | `/admin/**`, `/audit` |
| `page.read` | `/deploy`, `/forms`, `/codegen`, `/language`, `/skin` |
| `config.read` | `GET /config`, `GET /config/entries` |
| `config.write` | `POST /config/entries` (audited as `config.override`) |
| `registry.read` | `GET /registry/services`, `/services` |
| `registry.write` | `POST /registry/services` (audited as `registry.register`) |
| `task.write` | `/tasks/**` (needs `X-Tenant-Id` **and** an `operator_tenant_grant` for that tenant or `*`; missing either is 403) |
| `operator.manage` | `/api/v1/operators/**` except `POST .../me/password` (list/create/disable/enable/change other password/tenant grants) |
| `tenant.manage` | `POST/PATCH /api/v1/tenants/**` (create/rename/disable/enable); list/get use `admin.read` |
| `org.read` | `GET /api/v1/org/units`, `GET /api/v1/org/memberships` (`tenantId` required); console `/org` |
| `org.write` | `PUT /api/v1/org/units/{orgUnitId}`, `PUT/DELETE /api/v1/org/memberships` (`tenantId` + operator–tenant grant; audited); console `/org` write forms |

Roles: `platform-operator` holds the full catalog (including `operator.manage`, `tenant.manage`, `org.read`, and `org.write`). `platform-reader` holds `admin.read`, `page.read`, `config.read`, `declaration.read`, `org.read`, `registry.read` (no `org.write`). Role `platform.super-admin` is **isolated break-glass**: keep `role_permission` empty for it; when a subject holds that role, `JdbcOperatorDirectory` expands authorities to the **full** `platform_permission` catalog at load time (no catalog rows are inserted). Ordinary `JdbcOperatorAdmin.create` and `OperatorBootstrap` **refuse** to mint or assign this role. A signed-in operator without the permission gets 403; no login or a wrong password gets 401.

角色：`platform-operator` 拥有完整目录（含 `operator.manage`、`tenant.manage`、`org.read`、`org.write`）。`platform-reader` 含 `admin.read`、`page.read`、`config.read`、`declaration.read`、`org.read`、`registry.read`（无 `org.write`）。`platform.super-admin` 为**隔离破窗**：对该角色保持 `role_permission` 为空；主体持有该角色时，`JdbcOperatorDirectory` 在加载时将授权展开为完整 `platform_permission` 目录（不插入目录行）。普通 `JdbcOperatorAdmin.create` 与 `OperatorBootstrap` **拒绝**签发或分配该角色。已登录但缺权限得 403；未登录或口令错误得 401。

Permission **tiers** (not separate login account types) separate console vs business operators; break-glass uses the dedicated super-admin bootstrap below.

权限**分层**（非主拆两套登录账号）区分控制台与业务操作员；破窗使用下方专用超管开通。

## Local operator (local only) / 本地操作员（只用于本地）

Start platform-app with `SPRING_PROFILES_ACTIVE=local`. `LocalOperatorSeeder` then writes one operator with role `platform-operator`, login `PLATFORM_OPERATOR_NAME` (default `platform-operator`) and password `PLATFORM_OPERATOR_PASSWORD` (default `change-me`), and logs a `LOCAL ONLY` warning. Without that profile nothing is seeded.

用 `SPRING_PROFILES_ACTIVE=local` 启动 platform-app 时，`LocalOperatorSeeder` 写入一位持有 `platform-operator` 的操作员，并打印 `LOCAL ONLY` 警告。没有这个 profile 时不写入任何人。

## Provisioning an operator (bootstrap) / 开通操作员（bootstrap）

Prefer the one-shot bootstrap on `platform-app`. It is **off by default** and must be enabled explicitly. It writes the same tables as `LocalOperatorSeeder` (account, subject, platform identity, credential, role), upserts by login name, refuses passwords shorter than 8 characters, prints loud `BOOTSTRAP` warnings, then **exits** so a normal start never keeps the flag on.

优先用 `platform-app` 的一次性开通。**默认关闭**，必须显式打开。写入表与 `LocalOperatorSeeder` 相同，按登录名幂等更新，口令短于 8 字符拒绝，打印醒目的 `BOOTSTRAP` 警告，然后**退出进程**，避免日常启动常开此开关。

```bash
export PLATFORM_OPERATOR_LOGIN=ops-1
export PLATFORM_OPERATOR_PASSWORD='a-long-enough-secret'
# optional: PLATFORM_OPERATOR_ROLE=platform-reader  (default platform-operator)
java -jar platform-app.jar \
  --platform.operator.bootstrap=true \
  --spring.main.web-application-type=none
```

Properties (when the flag is on): `platform.operator.login` / `platform.operator.password` / `platform.operator.role` (env names above). Do **not** set `platform.operator.bootstrap=true` in compose or k8s Deployment defaults — use a one-shot Job or a manual run, then start the app without the flag.

属性（仅在打开开关时）：登录名 / 口令 / 角色。**不要**在 compose 或 k8s Deployment 默认里打开该开关——用一次性 Job 或手工跑一次，再正常启动。

### Break-glass super-admin bootstrap / 破窗超管开通

Dedicated one-shot path for role `platform.super-admin` (**off by default**). Ordinary bootstrap / `operator.manage` create **cannot** assign this role. Upserts fixed ids (`account-super-admin` / `subject-super-admin` / `identity-super-admin`), writes `subject_role` + tenant grant `*`, leaves `role_permission` empty, prints loud `BOOTSTRAP SUPER-ADMIN` warnings, then **exits**.

专用一次性路径，角色 `platform.super-admin`（**默认关闭**）。普通开通 / `operator.manage` 创建**不能**分配该角色。幂等写入固定标识，写 `subject_role` 与租户通配 `*`，保持 `role_permission` 为空，打印醒目警告后**退出**。

```bash
export PLATFORM_SUPER_ADMIN_LOGIN=break-glass
export PLATFORM_SUPER_ADMIN_PASSWORD='a-long-enough-secret'
java -jar platform-app.jar \
  --platform.operator.super-admin-bootstrap=true \
  --spring.main.web-application-type=none
```

Properties: `platform.operator.super-admin-login` / `platform.operator.super-admin-password`. Do **not** set `platform.operator.super-admin-bootstrap=true` in compose or k8s defaults.

属性：登录名 / 口令。**不要**在 compose 或 k8s 默认打开该开关。

### Hand-written SQL (still works) / 手写 SQL（仍可用）

Insert rows yourself if you cannot run the jar. The hash must carry its encoder id; `{bcrypt}` with a `$2a$`/`$2b$`/`$2y$` hash works (for example `htpasswd -bnBC 12 "" 'the-password' | tr -d ':\n'`).

不能跑 jar 时仍可自己插入表行。摘要必须带编码器标识，例如 `{bcrypt}` 加上 bcrypt 摘要。

```sql
INSERT INTO account (account_id, login_name, account_state) VALUES ('account-ops-1', 'ops-1', 'ACTIVE');
INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES ('subject-ops-1', 'Ops One', 'PERSON');
INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
VALUES ('identity-ops-1', 'account-ops-1', 'subject-ops-1', 'platform', 'ACTIVE');
INSERT INTO operator_credential (account_id, password_hash) VALUES ('account-ops-1', '{bcrypt}$2y$12$...');
INSERT INTO subject_role (subject_id, role_name) VALUES ('subject-ops-1', 'platform-reader');
```

sample-consumer signs in to platform-app with `PLATFORM_OPERATOR_NAME` / `PLATFORM_OPERATOR_PASSWORD`; give that account `registry.write` and `config.read` (the `platform-operator` role has both).

sample-consumer 用 `PLATFORM_OPERATOR_NAME` / `PLATFORM_OPERATOR_PASSWORD` 登录 platform-app；给这个账号 `registry.write` 和 `config.read`（`platform-operator` 角色两者都有）。


## Operator management API / 操作员管理接口

| Method + path | Who | Notes |
| --- | --- | --- |
| `GET /api/v1/operators` | `operator.manage` | List login, state, role |
| `POST /api/v1/operators` | `operator.manage` | Create (login, password ≥ 8, role); refuses `platform.super-admin`; no tenant grants until assigned |
| `POST /api/v1/operators/me/password` | any signed-in | Body: `currentPassword`, `newPassword` |
| `POST /api/v1/operators/{login}/password` | `operator.manage` | Body: `newPassword` |
| `POST /api/v1/operators/{login}/disable` | `operator.manage` | Sets `account_state=DISABLED`; cannot disable self |
| `POST /api/v1/operators/{login}/enable` | `operator.manage` | Sets account + platform identity ACTIVE |
| `GET/PUT /api/v1/operators/{login}/tenants` | `operator.manage` | Replace body `{ "tenantIds": ["t1", "*"] }` |

Bootstrap and `local` seed still grant tenant `*` to the provisioned operator so existing task flows work on a laptop.

开通与 `local` 种子仍给开通的操作员写租户通配 `*`，本机任务流可继续用。


## Explainable context checks (AX-1 + AX-2 + AX-3) / 可解释上下文判定（AX-1 + AX-2 + AX-3）

Declaration and form/entity/page API paths evaluate through `AccessChecker` → `AccessDecision`:
**subjectId**, **tenantId**, **orgScope** (`OrgScope` or null), **resource** (kind+id), **action**,
**matchedPermission**, **allowed**, **denyReason**.

声明与表单/实体/页面 API 经 `AccessChecker` 产出 `AccessDecision`（上列字段）。

### Policy engine port (AX-3) / 策略引擎端口（AX-3）

`PolicyEngine.evaluate(principal, requiredPermission, action, resource, context)` → `AccessDecision`.

| Subset field | Cedar | Casbin | Subjex |
| --- | --- | --- | --- |
| Subject | `principal` | `sub` | `PolicyPrincipal` (subjectId + permissionNames) |
| Action | `action` | `act` | `AccessAction` + `requiredPermission` (permission = Casbin act subset) |
| Resource | `resource` | `obj` | `PolicyResource` (kind/id + attrs e.g. orgUnitId, tenantId) |
| Context / domain | `context` | domain | `PolicyContext` (tenantId, tenantScoped, OrgScope) |

First adapter: `SqlRbacPolicyEngine` → existing `AccessChecker` / SQL RBAC (no fork). Wired in `PlatformWiring`; org membership write scope uses the port. **No Cedar/Casbin jars yet** — full engines are later swap-ins. No custom DSL.

首适配器 `SqlRbacPolicyEngine` 委托 `AccessChecker`；组织成员写范围已接端口。尚未引入 Cedar/Casbin 依赖；完整引擎后置换入；无自研 DSL。

### Org scope (AX-2) / 组织范围（AX-2）

Mode **`SELF_AND_DESCENDANTS`** only: roots = caller's **ACTIVE** `org_membership` units in the tenant;
`unitIds` = roots + descendants via `org_unit.parent_org_unit_id` (in-memory BFS, H2-safe).
No ACTIVE memberships → `orgScope` null / unspecified → **no org filter** (platform operators without org rows stay unscoped).
No new role names for scope. No Zanzibar.

仅模式「本部门及下级」；无 ACTIVE 成员则不过滤。不加角色名表达范围；不上 Zanzibar。

Org vertical: `GET /api/v1/org/units|memberships` filters to `unitIds` when scoped; PUT/DELETE deny with
`org_out_of_scope` when the target unit is outside scope (creates must attach under an in-scope parent).

组织竖切：有范围时 GET 过滤；写越界结构化拒绝 `org_out_of_scope`。

Deny reasons: `permission_blank`, `permission_missing`, `tenant_missing`, `tenant_not_granted`, `org_out_of_scope`.
HTTP 403 returns `FormProblemDocument` with `kind: permission_denied`, backward-compatible `permission`,
plus decision fields including structured `orgScope` (`mode`, `rootUnitIds`, `unitIds`).
Refuse paths may audit `access.deny` with target `kind:id:denyReason`.

拒绝原因见上。403 保留 `permission` 并附带决策字段（含结构化 `orgScope`）；可记审计 `access.deny`。

Cedar/Casbin **subset port** landed (AX-3); jars deferred. Prefer `PolicyEngine` for new callers.

Cedar/Casbin **子集端口**已落地（AX-3）；依赖后置。新调用方优先走 `PolicyEngine`。

## Operator–tenant grants / 操作员—租户授权

Table `operator_tenant_grant (subject_id, tenant_id)`. Wildcard `*` means every tenant. Tenant-scoped filters and declaration `tenantScoped` checks call `OperatorTenantAccess` after `TenantGuard` — fail-closed when the grant is missing.

表 `operator_tenant_grant`。通配 `*` 表示全部租户。租户拦截与声明 `tenantScoped` 在 `TenantGuard` 之后核对授权，无授权失败关闭。

## Reading the audit / 阅读审计

`GET /admin/audit` returns the newest 200 entries as JSON: time, tenant, actor identity and login, action, target, outcome. `GET /audit` shows the same rows as a page, with the action and outcome in plain words.

`GET /admin/audit` 以 JSON 返回最新的 200 条：时间、租户、操作者身份与登录名、动作、对象、结果。`GET /audit` 用页面展示同样的行，动作和结果用白话。

## Tests / 测试

Tests run the real Flyway scripts on H2 in PostgreSQL and MySQL compatibility mode (test scope only). That proves the SQL without Docker; it is not the live-vendor proof, which stays in the Docker-gated `VendorStartupTest`.

测试在 H2 的 PostgreSQL 与 MySQL 兼容模式（只在测试范围）上执行真实的 Flyway 脚本，不需要 Docker 就能证明 SQL；真实厂商的证明仍由需要 Docker 的 `VendorStartupTest` 负责。
