-- Operator login failure lockout (Lockout-1) — per-login counters; password path.
-- 操作员登录失败锁定（Lockout-1）——按登录名计数；口令路径。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (no vendor-only types).
-- MySQL 8.4 与 PostgreSQL 16 共用风格（无厂商专有类型）。
-- login_name matches account.login_name comparison (trim in API; case as typed).
-- login_name 与 account.login_name 比对一致（接口 trim；大小写按原样）。

CREATE TABLE operator_login_lockout (
    login_name VARCHAR(256) NOT NULL,
    failure_count INT NOT NULL,
    locked_until TIMESTAMP,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (login_name)
);
