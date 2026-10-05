-- Record which declaration version produced each accepted submission.
-- 记录每条已接受提交对应的声明版本。
-- Correlates history with checked-in form YAML; not an online schema editor.
-- 用历史对照检入的表单 YAML；不是在线 schema 编辑器。
-- Existing rows (if any) backfill to 1 — the first checked-in sample version.
-- 已有行（若有）回填为 1 —— 首批样例声明版本。

ALTER TABLE form_submission ADD COLUMN declaration_version INT NOT NULL DEFAULT 1;
