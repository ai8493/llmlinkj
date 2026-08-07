-- 声明 openai-responses 为合法的 backend_config.protocol 值
-- 用途:让 responses 入站 -> openai-responses 后端走 Responses API 全链路
-- 说明:backend_config.protocol 是 TEXT 字段,无枚举约束,本迁移不改 schema
--       仅作为合法值声明,供运维参考。实际校验在 BackendAdapterFactory switch。
-- V7/V8 已被占用,本迁移使用 V9。

-- 不插入任何数据:openai-responses 后端由运维通过 /api/backends (POST) 按需配置
-- 示例:POST /api/backends {"name":"openai-responses-official","protocol":"openai-responses",...}
-- 配套:/api/protocols 加 responses -> openai-responses-official 映射
