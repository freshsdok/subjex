-- WP8-3: request-level artifact expiry and WP3 object ref (slash-free key).
-- WP8-3：申请级产物过期时间与 WP3 对象引用（对象键不含斜杠）。
ALTER TABLE data_export_request ADD COLUMN expires_at TIMESTAMP;
ALTER TABLE data_export_request ADD COLUMN artifact_object_key VARCHAR(256);
