-- Async personal-data export plus keyset indexes for the lists this slice pages.
-- 异步个人资料导出，以及本切片分页列表所需的键集索引。
-- GENERATING / FAILED are request statuses only (no new columns). Artifact TTL stays request-level.
-- GENERATING / FAILED 只是申请状态（不加新列）。产物有效期仍按申请行。
-- No Elasticsearch, no questionnaire-CSV or tenant-ZIP changes, no medical columns, no vendor-only types.
-- 不加 Elasticsearch，不改问卷 CSV 或租户 ZIP，无医疗列，无厂商专有类型。
-- Shared by MySQL 8.4 and PostgreSQL 16 style.
-- MySQL 8.4 与 PostgreSQL 16 共用风格。

-- Export request list (tenant, newest/oldest keyset) and worker pickup.
-- 导出申请列表（按租户键集）与后台领取。
CREATE INDEX data_export_request_tenant_created_id
    ON data_export_request (tenant_id, created_at, request_id);

CREATE INDEX data_export_request_status_updated_id
    ON data_export_request (request_status, updated_at, request_id);

-- Subject-owned questionnaire responses for the generic exporter cursor.
-- 通用导出器按主体键集翻页答卷。
CREATE INDEX questionnaire_response_tenant_subject_submitted
    ON questionnaire_response (tenant_id, respondent_subject_id, submitted_at, response_id);

-- Inbox mailbox keyset (created_at + entry_id), without read_at in the middle.
-- 站内信邮箱键集（创建时间 + 条目标识），中间不含已读时间。
CREATE INDEX inbox_entry_recipient_created_id
    ON inbox_entry (tenant_id, recipient_realm, recipient_subject_id, created_at, entry_id);

-- Owned objects keyset for the exporter.
-- 导出器翻页主体自有对象。
CREATE INDEX object_descriptor_owner_created_id
    ON object_descriptor (tenant_id, owner_subject_id, created_at, object_id);
