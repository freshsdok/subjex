-- Shared storage for discovery, config overrides, and the lock, shared by MySQL and PostgreSQL.
-- 发现、配置覆盖和锁的共享存放，MySQL 与 PostgreSQL 共用。
-- Every process pointed at this database sees the same rows, also after a restart.
-- 指向同一个库的每个进程看到同样的行，重启之后也一样。

-- service_endpoint: the address a named service registered last.
-- service_endpoint：一个具名服务最近一次登记的地址。
CREATE TABLE service_endpoint (
    service_name VARCHAR(128) NOT NULL,
    host VARCHAR(256) NOT NULL,
    port INT NOT NULL,
    registered_at TIMESTAMP NOT NULL,
    PRIMARY KEY (service_name)
);

-- config_override: the value that wins over local application config for one named key.
-- config_override：对一个具名键压过本地应用配置的那个值。
CREATE TABLE config_override (
    config_key VARCHAR(256) NOT NULL,
    config_value VARCHAR(4000) NOT NULL,
    overridden_at TIMESTAMP NOT NULL,
    PRIMARY KEY (config_key)
);

-- platform_lock: who holds this named lock, and until when. An expired row may be taken over.
-- platform_lock：谁持有这把具名锁，持有到什么时候。过期的行可以被接手。
CREATE TABLE platform_lock (
    lock_name VARCHAR(256) NOT NULL,
    owner_token VARCHAR(256) NOT NULL,
    held_until TIMESTAMP NOT NULL,
    PRIMARY KEY (lock_name)
);
