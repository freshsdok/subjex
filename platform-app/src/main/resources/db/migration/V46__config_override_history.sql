-- Config-6a / ADR 0012: append-only override history (namespace+key+revision).
-- Config-6a / ADR 0012：覆盖历史只追加（命名空间+键+修订号）。
-- Effective SoT remains config_override; this table is audit evidence only.
-- 生效真相仍是 config_override；本表仅作审计证据。

CREATE TABLE config_override_history (
    namespace VARCHAR(128) NOT NULL,
    config_key VARCHAR(256) NOT NULL,
    config_value VARCHAR(4000) NOT NULL,
    revision BIGINT NOT NULL,
    overridden_at TIMESTAMP NOT NULL,
    PRIMARY KEY (namespace, config_key, revision)
);

CREATE INDEX config_override_history_ns_key_time
    ON config_override_history (namespace, config_key, overridden_at);
