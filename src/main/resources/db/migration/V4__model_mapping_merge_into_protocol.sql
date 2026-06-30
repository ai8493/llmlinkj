-- 将 model_mapping 表与 protocol_mapping 表绑定：
-- 旧表主键 request_model（全局映射），新表主键 (client_protocol, backend_cfg_name, request_model)
-- 旧表数据为 Seeder 从配置层灌入的镜像，配置层仍为路由数据源，旧数据直接丢弃
-- （与 V3 处理 protocol_mapping 一致）

DROP TABLE model_mapping;
CREATE TABLE model_mapping (
    client_protocol      TEXT NOT NULL,
    backend_cfg_name     TEXT NOT NULL,
    request_model        TEXT NOT NULL,
    actual_model         TEXT NOT NULL,
    updated_at           TEXT NOT NULL,
    PRIMARY KEY (client_protocol, backend_cfg_name, request_model)
);
