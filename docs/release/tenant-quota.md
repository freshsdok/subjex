# Tenant quota hard caps / 租户配额硬顶（P5）

## Caps / 上限

| Cap | Config | Default | On exceed |
| --- | --- | --- | --- |
| Daily submit count | `platform.tenant-quota.daily-submit-limit` / `PLATFORM_TENANT_DAILY_SUBMIT_LIMIT` | 10000 | **429** + audit `tenant.quota` / `tenant-quota-daily-submit` |
| Storage row count | `platform.tenant-quota.storage-row-limit` / `PLATFORM_TENANT_STORAGE_ROW_LIMIT` | 100000 | **429** + audit `tenant.quota` / `tenant-quota-storage-rows` |

Daily count = `platform_task` + `form_submission` for the tenant since UTC midnight.
日提交 = 该租户自 UTC 零点起的任务行 + 表单提交行。

Storage rows = `platform_task` + `form_submission` + `outbox_event` for the tenant.
存储行 = 该租户的任务 + 表单提交 + 出箱事件。

Checked on form submit and task submit. **No billing.**
在表单提交与任务提交时检查。**不计费。**

Flyway `V20__form_submission_tenant_quota.sql` adds `form_submission.tenant_id`.
