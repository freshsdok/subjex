# Operators, permissions, and audit / 操作员、权限与审计

## Tables / 表

Flyway `V2__operator_permission.sql` adds the catalog. It writes no person and no password.

Flyway `V2__operator_permission.sql` 加入目录，不写入任何人，也不写入任何口令。

| Table / 表 | One row is / 一行是 |
| --- | --- |
| `platform_permission` | one named thing an operator may do / 操作员能做的一件具名的事 |
| `platform_role` | a named bundle of permissions / 一组具名权限 |
| `role_permission` | this role grants this permission / 这个角色授予这项权限 |
| `subject_role` | this subject holds this role / 这个主体持有这个角色 |
| `operator_credential` | the password hash an account signs in with / 账号登录用的口令摘要 |

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
| `task.write` | `/tasks/**` (still needs `X-Tenant-Id`; a missing tenant is 403) |

Roles: `platform-operator` holds all seven. `platform-reader` holds `admin.read`, `page.read`, `config.read`, `registry.read`. A signed-in operator without the permission gets 403; no login or a wrong password gets 401.

角色：`platform-operator` 拥有全部七项。`platform-reader` 只有四项读权限。已登录但缺权限得 403；未登录或口令错误得 401。

## Local operator (local only) / 本地操作员（只用于本地）

Start platform-app with `SPRING_PROFILES_ACTIVE=local`. `LocalOperatorSeeder` then writes one operator with role `platform-operator`, login `PLATFORM_OPERATOR_NAME` (default `platform-operator`) and password `PLATFORM_OPERATOR_PASSWORD` (default `change-me`), and logs a `LOCAL ONLY` warning. Without that profile nothing is seeded.

用 `SPRING_PROFILES_ACTIVE=local` 启动 platform-app 时，`LocalOperatorSeeder` 写入一位持有 `platform-operator` 的操作员，并打印 `LOCAL ONLY` 警告。没有这个 profile 时不写入任何人。

## Provisioning an operator elsewhere / 在别处开通操作员

Insert rows yourself. The hash must carry its encoder id; `{bcrypt}` with a `$2a$`/`$2b$`/`$2y$` hash works (for example `htpasswd -bnBC 12 "" 'the-password' | tr -d ':\n'`).

自己插入表行。摘要必须带编码器标识，例如 `{bcrypt}` 加上 bcrypt 摘要。

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

## Reading the audit / 阅读审计

`GET /admin/audit` returns the newest 200 entries as JSON: time, tenant, actor identity and login, action, target, outcome. `GET /audit` shows the same rows as a page, with the action and outcome in plain words.

`GET /admin/audit` 以 JSON 返回最新的 200 条：时间、租户、操作者身份与登录名、动作、对象、结果。`GET /audit` 用页面展示同样的行，动作和结果用白话。

## Tests / 测试

Tests run the real Flyway scripts on H2 in PostgreSQL and MySQL compatibility mode (test scope only). That proves the SQL without Docker; it is not the live-vendor proof, which stays in the Docker-gated `VendorStartupTest`.

测试在 H2 的 PostgreSQL 与 MySQL 兼容模式（只在测试范围）上执行真实的 Flyway 脚本，不需要 Docker 就能证明 SQL；真实厂商的证明仍由需要 Docker 的 `VendorStartupTest` 负责。
