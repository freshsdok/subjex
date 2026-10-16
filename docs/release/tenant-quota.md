# Tenant quota hard caps / 租户配额硬顶（P5 + TN-1）

## Caps / 上限

| Cap | Config | Default | On exceed |
| --- | --- | --- | --- |
| Daily submit count | `platform.tenant-quota.daily-submit-limit` / `PLATFORM_TENANT_DAILY_SUBMIT_LIMIT` | 10000 | **429** + audit `tenant.quota` / `tenant-quota-daily-submit` |
| Storage row count | `platform.tenant-quota.storage-row-limit` / `PLATFORM_TENANT_STORAGE_ROW_LIMIT` | 100000 | **429** + audit `tenant.quota` / `tenant-quota-storage-rows` |

Daily count = `platform_task` + `form_submission` for the tenant since UTC midnight.
日提交 = 该租户自 UTC 零点起的任务行 + 表单提交行。

Storage rows (TN-1) = `platform_task` + `form_submission` + `outbox_event` + **`entity_record`** + **`entity_blob`**.
存储行（TN-1）= 任务 + 表单提交 + 出箱 + **实体行** + **附件元数据行**。

Checked on form submit and task submit. **No billing.**
在表单提交与任务提交时检查。**不计费。**

Flyway `V20__form_submission_tenant_quota.sql` adds `form_submission.tenant_id`.
Flyway **`V36__tenant_quota_override.sql`**: per-tenant overrides (`daily_submit_limit` / `storage_row_limit`; null = use global).

## Per-tenant override (TN-1) / 按租户覆盖

| API | Auth | Notes |
| --- | --- | --- |
| `GET /api/v1/tenants/{tenantId}/quota` | `admin.read` | Usage + effective/global/override limits |
| `PUT /api/v1/tenants/{tenantId}/quota` | `tenant.manage` | Body `{ dailySubmitLimit?, storageRowLimit? }`; null field clears that override; both null deletes row. Audits `tenant.quota.override`. |

Globals remain defaults when no override (or when that column is null).
无覆盖（或该列为 null）时用全局默认。

## Follow-on / 后续

See [`docs/tenant/tn-progress.md`](../tenant/tn-progress.md) (TN-2 export/delete → TN-3 isolation CI).

Export/erase (TN-2): see [`docs/tenant/tn-progress.md`](../tenant/tn-progress.md).
