-- 放开 backend_config.api_key 和 default_model 的 NOT NULL 约束，
-- 使 init.sql 种子可写入 NULL（api_key 待运维填入真实 key；default_model 同样留空待填）
-- SQLite 不支持 ALTER COLUMN，沿用 V2 的「新建临时表 → 复制 → 删旧表 → 重命名」流程

CREATE TABLE backend_config_new (
    name                    TEXT PRIMARY KEY,
    protocol                TEXT NOT NULL,
    api_key                 TEXT,
    base_url                TEXT NOT NULL,
    default_model           TEXT,
    default_max_tokens      INTEGER,
    connect_timeout         INTEGER NOT NULL,
    read_timeout            INTEGER NOT NULL,
    write_timeout           INTEGER NOT NULL,
    max_idle_connections    INTEGER NOT NULL DEFAULT 20,
    keep_alive_duration     INTEGER NOT NULL DEFAULT 300,
    updated_at              TEXT NOT NULL
);

INSERT INTO backend_config_new
    (name, protocol, api_key, base_url, default_model, default_max_tokens,
     connect_timeout, read_timeout, write_timeout, max_idle_connections,
     keep_alive_duration, updated_at)
SELECT name, protocol, api_key, base_url, default_model, default_max_tokens,
       connect_timeout, read_timeout, write_timeout, max_idle_connections,
       keep_alive_duration, updated_at
FROM backend_config;

DROP TABLE backend_config;
ALTER TABLE backend_config_new RENAME TO backend_config;
