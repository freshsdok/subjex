-- Generation claim/lease so a worker does not hold FOR UPDATE for the whole stream.
-- 生成认领/租约：工作线程不必在整段写入期间一直持有 FOR UPDATE。
-- An expired lease can be reclaimed; mid-write resume is not added (put overwrites).
-- 租约过期后可被重新认领；不发明中途续写（put 会整对象覆盖）。
-- Subject-wide consent history keyset for the personal-data exporter.
-- 个人资料导出器按主体键集翻页同意历史。
-- No Elasticsearch, no questionnaire-CSV or tenant-ZIP changes, no medical columns.
-- 不加 Elasticsearch，不改问卷 CSV 或租户 ZIP，无医疗列。
-- Shared by MySQL 8.4 and PostgreSQL 16 style.
-- MySQL 8.4 与 PostgreSQL 16 共用风格。

ALTER TABLE data_export_request
    ADD COLUMN generation_lease_owner VARCHAR(64);

ALTER TABLE data_export_request
    ADD COLUMN generation_lease_expires_at TIMESTAMP;

CREATE INDEX data_export_request_generation_lease
    ON data_export_request (request_status, generation_lease_expires_at, request_id);

CREATE INDEX consent_event_tenant_subject_occurred
    ON consent_event (tenant_id, subject_id, occurred_at, event_id);
