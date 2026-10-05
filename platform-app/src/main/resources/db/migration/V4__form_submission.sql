-- Accepted form submissions shared by MySQL and PostgreSQL.
-- 已接受的表单提交，MySQL 与 PostgreSQL 共用。
-- History is listed by form_key; this is not an online form library.
-- 按 form_key 列历史；这不是在线表单库。

CREATE TABLE form_submission (
  submission_id VARCHAR(64) NOT NULL,
  form_key VARCHAR(128) NOT NULL,
  actor_identity_id VARCHAR(64) NOT NULL,
  login_name VARCHAR(128) NOT NULL,
  values_json VARCHAR(8000) NOT NULL,
  result_summary VARCHAR(512) NOT NULL,
  submitted_at TIMESTAMP NOT NULL,
  PRIMARY KEY (submission_id)
);

CREATE INDEX form_submission_form_key_submitted ON form_submission (form_key, submitted_at);
