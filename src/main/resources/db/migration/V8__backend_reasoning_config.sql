-- 给 backend_config 加 4 个 reasoning/thinking 配置列,支持按后端能力映射 effort
-- reasoning_effort_mode: passthrough(默认)/low_high/openrouter/deepseek,控制 IR reasoningEffort -> 后端实际 effort 的映射规则
-- reasoning_effort_default: 客户端未传 effort 时的默认值(low/medium/high/xhigh/none/minimal)
-- thinking_default_type: 客户端未传 thinking 时的默认 type(enabled/disabled/adaptive)
-- thinking_default_budget: thinking_default_type=enabled 时的默认 budget_tokens

ALTER TABLE backend_config ADD COLUMN reasoning_effort_mode TEXT;
ALTER TABLE backend_config ADD COLUMN reasoning_effort_default TEXT;
ALTER TABLE backend_config ADD COLUMN thinking_default_type TEXT;
ALTER TABLE backend_config ADD COLUMN thinking_default_budget INTEGER;
