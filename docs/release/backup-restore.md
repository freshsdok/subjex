# Backup and restore drill / 备份恢复演练（P6）

## Gate / 门禁

**No successful drill record ⇒ do not cut `v0.1.0-alpha.1`.**
**没有成功演练记录 ⇒ 不得打 `v0.1.0-alpha.1` tag。**

Record files live under `docs/release/drills/`. A stub named `DRILL-PENDING.md` means the gate is still closed.
记录在 `docs/release/drills/`。若存在 `DRILL-PENDING.md`，门禁仍关闭。

## Scope / 范围

1. Full backup of PostgreSQL **or** MySQL used by `platform-app` Flyway.
2. Restore into an **empty** database.
3. Confirm Flyway schema version matches the backup source (`flyway_schema_history`).
4. Confirm an old form submission remains readable at its original `declaration_version`.

## PostgreSQL procedure / PostgreSQL 步骤

```shell
# Backup / 备份
pg_dump -Fc -h "$HOST" -U subjex -d subjex -f subjex-$(date +%Y%m%d).dump
sha256sum subjex-*.dump > subjex.dump.sha256

# Empty restore / 空库恢复
createdb -h "$HOST" -U postgres subjex_restore
pg_restore -h "$HOST" -U subjex -d subjex_restore --clean --if-exists subjex-YYYYMMDD.dump

# Flyway version / Flyway 版本
psql -h "$HOST" -U subjex -d subjex_restore -c \
  "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"

# Old submission declaration_version / 旧提交声明版本
psql -h "$HOST" -U subjex -d subjex_restore -c \
  "SELECT submission_id, form_key, declaration_version FROM form_submission ORDER BY submitted_at LIMIT 5;"
```

## MySQL procedure / MySQL 步骤

```shell
mysqldump -h "$HOST" -u subjex -p --single-transaction --routines subjex \
  | tee subjex-$(date +%Y%m%d).sql | sha256sum > subjex.sql.sha256
mysql -h "$HOST" -u subjex -p -e "CREATE DATABASE subjex_restore;"
mysql -h "$HOST" -u subjex -p subjex_restore < subjex-YYYYMMDD.sql
mysql -h "$HOST" -u subjex -p subjex_restore -e \
  "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
```

## Record template / 记录模板

Copy to `docs/release/drills/YYYY-MM-DD-backup-restore.md` and fill:

| Field | Value |
| --- | --- |
| Date (Asia/Shanghai) | |
| Engine + version | PostgreSQL 16 / MySQL 8.4 |
| Backup file + SHA-256 | |
| Source Flyway tip | e.g. `20` |
| Restore Flyway tip | must match |
| Sample `declaration_version` checked | |
| Operator | |
| Result | PASS / FAIL |

Then **delete** `docs/release/drills/DRILL-PENDING.md` only after PASS.
仅在 PASS 后删除 `DRILL-PENDING.md`。
