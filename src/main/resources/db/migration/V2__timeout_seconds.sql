-- 将 backend_config 表的 4 个超时/保活字段从 TEXT（Duration 字符串如 "10s"）改为 INTEGER（秒数）。
-- SQLite 不支持 ALTER COLUMN，采用「新建临时表 → 复制 → 删旧表 → 重命名」流程。
-- 旧 Duration 字符串无法可靠转换成秒数，迁移时对这 4 列统一置默认值
-- （对应原 10s/10m/30s/5m）。

CREATE TABLE backend_config_new (
    name                    TEXT PRIMARY KEY,
    protocol                TEXT NOT NULL,
    api_key                 TEXT NOT NULL,
    base_url                TEXT NOT NULL,
    default_model           TEXT NOT NULL,
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
       10, 600, 30, max_idle_connections,
       300, updated_at
FROM backend_config;

DROP TABLE backend_config;
ALTER TABLE backend_config_new RENAME TO backend_config;
