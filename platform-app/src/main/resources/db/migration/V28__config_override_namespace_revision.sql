-- Config center namespaces + revision (Config-5b).
-- 配置中心命名空间与修订号（Config-5b）。
-- Existing flat rows land in namespace 'default' with revision 1.
-- 既有扁平行落入命名空间 default，修订号为 1。
-- Shared by MySQL 8.4 and PostgreSQL 16 style (recreate table for composite PK).
-- MySQL 8.4 与 PostgreSQL 16 共用（重建表以换复合主键）。

CREATE TABLE config_override__new (
    namespace VARCHAR(128) NOT NULL,
    config_key VARCHAR(256) NOT NULL,
    config_value VARCHAR(4000) NOT NULL,
    revision BIGINT NOT NULL,
    overridden_at TIMESTAMP NOT NULL,
    PRIMARY KEY (namespace, config_key)
);

INSERT INTO config_override__new (namespace, config_key, config_value, revision, overridden_at)
SELECT 'default', config_key, config_value, 1, overridden_at FROM config_override;

DROP TABLE config_override;

ALTER TABLE config_override__new RENAME TO config_override;
