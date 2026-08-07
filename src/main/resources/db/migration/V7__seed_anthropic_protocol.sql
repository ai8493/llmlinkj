-- 新增 anthropic 入站协议默认映射到 deepseek-openai 后端
-- 入站 Anthropic 协议 → IR → OpenAI 格式发给 DeepSeek,验证协议互转核心能力
-- INSERT OR IGNORE:主键已存在则跳过(幂等),防止运维在 V7 首次执行前已手动建同名映射时冲突
-- 再次启动时 Flyway 通过 flyway_schema_history 跳过已执行的 V7,运维填的数据保留不覆盖
INSERT OR IGNORE INTO protocol_mapping
(client_protocol, backend_cfg_name, enabled, updated_at)
VALUES('anthropic', 'deepseek-claude', 0, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO protocol_mapping
(client_protocol, backend_cfg_name, enabled, updated_at)
VALUES('anthropic', 'deepseek-openai', 0, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO protocol_mapping
(client_protocol, backend_cfg_name, enabled, updated_at)
VALUES('anthropic', 'minimax-claude', 0, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO protocol_mapping
(client_protocol, backend_cfg_name, enabled, updated_at)
VALUES('anthropic', 'minimax-openai', 0, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO protocol_mapping
(client_protocol, backend_cfg_name, enabled, updated_at)
VALUES('anthropic', 'step-claude', 0, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));
INSERT OR IGNORE INTO protocol_mapping
(client_protocol, backend_cfg_name, enabled, updated_at)
VALUES('anthropic', 'step-openai', 0, strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));