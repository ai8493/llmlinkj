-- 种子数据：首次启动空库时由 Flyway 自动执行一次，灌入后端/协议/模型映射的 NULL 骨架。
-- api_key 与 default_model 留空，待运维通过管理页面填入真实值。
-- INSERT OR IGNORE：主键已存在则跳过该行（幂等），防止运维在 V6 首次执行前已手动建同名配置时冲突。
-- 再次启动时 Flyway 通过 flyway_schema_history 跳过已执行的 V6，运维填的数据保留不覆盖。

INSERT OR IGNORE INTO backend_config
(name, protocol, api_key, base_url, default_model, default_max_tokens, connect_timeout, read_timeout, write_timeout, max_idle_connections, keep_alive_duration, updated_at)
VALUES('minimax-openai', 'openai', NULL, 'https://api.minimaxi.com/v1', 'MiniMax-M3', 100000, 10, 600, 30, 20, 300, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO backend_config
(name, protocol, api_key, base_url, default_model, default_max_tokens, connect_timeout, read_timeout, write_timeout, max_idle_connections, keep_alive_duration, updated_at)
VALUES('minimax-claude', 'anthropic', NULL, 'https://api.minimaxi.com/anthropic', 'MiniMax-M3', 100000, 10, 600, 30, 20, 300, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO backend_config
(name, protocol, api_key, base_url, default_model, default_max_tokens, connect_timeout, read_timeout, write_timeout, max_idle_connections, keep_alive_duration, updated_at)
VALUES('deepseek-openai', 'openai', NULL, 'https://api.deepseek.com/v1', 'deepseek-v4-flash', 100000, 10, 600, 30, 20, 300, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO backend_config
(name, protocol, api_key, base_url, default_model, default_max_tokens, connect_timeout, read_timeout, write_timeout, max_idle_connections, keep_alive_duration, updated_at)
VALUES('deepseek-claude', 'anthropic', NULL, 'https://api.deepseek.com/anthropic', 'deepseek-v4-flash', 100000, 10, 600, 30, 20, 300, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO backend_config
(name, protocol, api_key, base_url, default_model, default_max_tokens, connect_timeout, read_timeout, write_timeout, max_idle_connections, keep_alive_duration, updated_at)
VALUES('mimo-openai', 'openai', NULL, 'https://api.xiaomimimo.com/v1', 'mimo-v2.5-pro', 100000, 10, 600, 30, 20, 300, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO backend_config
(name, protocol, api_key, base_url, default_model, default_max_tokens, connect_timeout, read_timeout, write_timeout, max_idle_connections, keep_alive_duration, updated_at)
VALUES('mimo-claude', 'anthropic', NULL, 'https://api.xiaomimimo.com/anthropic', 'mimo-v2.5-pro', 100000, 10, 600, 30, 20, 300, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO backend_config
(name, protocol, api_key, base_url, default_model, default_max_tokens, connect_timeout, read_timeout, write_timeout, max_idle_connections, keep_alive_duration, updated_at)
VALUES('ark-claude', 'anthropic', NULL, 'https://ark.cn-beijing.volces.com/api/coding', 'glm-latest', 100000, 10, 600, 30, 20, 300, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO backend_config
(name, protocol, api_key, base_url, default_model, default_max_tokens, connect_timeout, read_timeout, write_timeout, max_idle_connections, keep_alive_duration, updated_at)
VALUES('siliconflow-claude', 'anthropic', NULL, 'https://api.siliconflow.cn', 'glm-5.1', 100000, 10, 600, 30, 20, 300, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));

INSERT OR IGNORE INTO protocol_mapping
(client_protocol, backend_cfg_name, enabled, updated_at)
VALUES('responses', 'deepseek-claude', 1, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO protocol_mapping
(client_protocol, backend_cfg_name, enabled, updated_at)
VALUES('responses', 'deepseek-openai', 0, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO protocol_mapping
(client_protocol, backend_cfg_name, enabled, updated_at)
VALUES('gemini', 'deepseek-claude', 1, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));

INSERT OR IGNORE INTO model_mapping
(client_protocol, backend_cfg_name, request_model, actual_model, updated_at)
VALUES('responses', 'deepseek-claude', 'gpt-5.5', 'deepseek-v4-pro', strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
