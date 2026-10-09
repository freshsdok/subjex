# Backup/restore drill — 2026-10-09 (P6)

| Field | Value |
| --- | --- |
| Date (Asia/Shanghai) | 2026-10-09 11:05 CST |
| Engine + version | PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2) |
| Backup file + SHA-256 | `subjex_drill-20261009.dump` — `4bd68bbd7bfe7db24ccc544648c6f651b487fc5a4b56b292f122c5d986871b4c` |
| Source Flyway tip | `21` (`declaration promote approval`) |
| Restore Flyway tip | `21` (match) |
| Sample `declaration_version` checked | `drill-sub-20261009-001` / `demo.ticket` / **3** (readable after restore) |
| Operator | box (local apt PostgreSQL 16 drill DB `subjex_drill` → empty `subjex_restore`) |
| Result | **PASS** |

## Procedure notes / 步骤说明

1. Installed PostgreSQL 16 from PGDG apt (HTTPS); started `16/main` on port 5432.
2. Created isolated role/DB `subjex_drill` (password drill-local only; not recorded here).
3. Applied Flyway via `flyway-maven-plugin:11.7.2:migrate` against `platform-app/src/main/resources/db/migration` → tip **V21**.
4. Seeded one `form_submission` with `declaration_version = 3`.
5. `pg_dump -Fc` → SHA-256 as above; `createdb subjex_restore`; `pg_restore --clean --if-exists`.
6. Verified restore `flyway_schema_history` tip **21** and sample row `declaration_version = 3`.

Dump artifact kept outside the git tree (`/workspace/subjex-drill-artifacts/`); only this PASS record is committed.
备份文件留在仓库外；仅提交本 PASS 记录。

Follows `docs/release/backup-restore.md`. Fake PASS forbidden — this run used a live PostgreSQL 16 instance on the box.
