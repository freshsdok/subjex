-- Keyset indexes for in-request operator questionnaire CSV and tenant ZIP streams.
-- 操作员问卷 CSV 与租户 ZIP 请求内流式导出所用的键集索引。
-- Reuses existing entity_record PK, form_submission (tenant_id, submitted_at), and tenant_host (tenant_id).
-- 复用 entity_record 主键、form_submission (tenant_id, submitted_at) 与 tenant_host (tenant_id)。
-- No Elasticsearch, no personal-export pipeline change, no medical columns.
-- 不加 Elasticsearch，不改个人导出流水线，无医疗列。
-- Shared by MySQL 8.4 and PostgreSQL 16 style.
-- MySQL 8.4 与 PostgreSQL 16 共用风格。

-- Questionnaire production CSV: tenant + questionnaire + submitted_at + response_id.
-- 问卷生产 CSV：租户 + 问卷 + 提交时间 + 答卷标识。
CREATE INDEX questionnaire_response_tenant_questionnaire_submitted
    ON questionnaire_response (tenant_id, questionnaire_id, submitted_at, response_id);

-- Tenant ZIP pages whose current indexes are id-only or do not match the stream order.
-- 租户 ZIP 中现有索引仅为标识、或与流式顺序不匹配的表。
CREATE INDEX platform_task_tenant_created_id
    ON platform_task (tenant_id, created_at, task_id);

CREATE INDEX outbox_event_tenant_occurred_id
    ON outbox_event (tenant_id, occurred_at, event_id);

CREATE INDEX entity_blob_tenant_created_id
    ON entity_blob (tenant_id, created_at, blob_id);
