-- 精简 protocol_mapping 表：
-- 旧字段 inbound_protocol/target_protocol/is_default/backend_name 弃用
-- 新字段 client_protocol(客户端协议) + backend_cfg_name(大模型配置名称) 为联合主键
-- 旧表数据为 Seeder 从配置层灌入的镜像，配置层仍为路由数据源，旧数据直接丢弃

DROP TABLE protocol_mapping;
CREATE TABLE protocol_mapping (
    client_protocol      TEXT NOT NULL,
    backend_cfg_name     TEXT NOT NULL,
    enabled              INTEGER NOT NULL DEFAULT 1,
    updated_at           TEXT NOT NULL,
    PRIMARY KEY (client_protocol, backend_cfg_name)
);
