# LLMLinkJ

大模型协议互转代理——同时对外暴露 OpenAI Chat、OpenAI Responses、Gemini 三套原生端点，对内适配多种后端（Gemini、OpenAI、Anthropic 等）。通过归一化中间表示（IR）解耦客户端协议与后端协议，实现任意客户端协议到任意后端的互转。配置由 SQLite 持久化，通过 `/admin` 管理 API 在线维护，无需改 yml 重启。

## 已实现协议转换

通过 IR 解耦，任意入站协议可转任意后端协议（9 条路径，✅ 表示已实现）：

| 入站 ＼ 后端 | OpenAI Chat | Anthropic |
|---------------|--------|-----------|
| OpenAI Chat        |        | ✅ |
| OpenAI Responses   | ✅      | ✅ |
| Gemini             | ✅      | ✅ |

> DeepSeek、MiniMax 等第三方模型走 OpenAI Chat/Anthropic 协议接入，归入「后端 OpenAI」一列。

## 架构

```
          ┌─ OpenAI --─┐
          ├─ Gemini --─┤
          └─ Responses ┘
                 │
                 ▼
          Controller (三套端点)
                 │
          ProtocolAdapter (OpenAI/Gemini/Responses ↔ IR)
                 │
            UnifiedChatRequest (IR)
                 │
          ProxyOrchestrator (路由)
                 │
          BackendAdapter (IR ↔ 后端 SDK)
                 │
           ┌─ OpenAI --─┐
           └─ Anthropic ┘
```

- **入站**：`OpenAiProtocolAdapter`、`GeminiProtocolAdapter`、`ResponsesProtocolAdapter` 将各自原生协议转为 IR
- **调度**：`ProxyOrchestrator` 根据入站协议和 model 名路由到对应后端
- **出站**：`BackendAdapter` 将 IR 转为后端原生协议，响应沿原路返回

### 互转 vs 单向代理

单向代理只能 OpenAI→Gemini，换个客户端协议就得重写。互转的核心是 IR：M 种客户端协议 + N 种后端 = M+N 个适配器，而非 M×N。

```
OpenAI Chat ──→ IR ──→ Gemini / DeepSeek / OpenAI / Anthropic  ✅
OpenAI Responses ──→ IR ──→ Gemini / DeepSeek / OpenAI / Anthropic  ✅
Gemini ──→ IR ──→ Gemini / DeepSeek / OpenAI / Anthropic  ✅
```

## 端点

### OpenAI 兼容端点

```bash
POST /v1/chat/completions          # 非流式
POST /v1/chat/completions          # 流式 (Accept: text/event-stream)
GET  /v1/models
```

请求/响应格式与 OpenAI Chat Completions API 一致。

```bash
curl http://localhost:8493/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d '{"model":"gemini-pro","messages":[{"role":"user","content":"你好"}]}'
```

### Gemini 原生端点

```bash
POST /v1beta/models/{model}:generateContent        # 非流式
POST /v1beta/models/{model}:streamGenerateContent  # 流式
GET  /v1beta/models
```

请求/响应格式与 Gemini API 一致。

```bash
curl http://localhost:8493/v1beta/models/gemini-pro:generateContent \
  -H "Content-Type: application/json" \
  -H "x-goog-api-key: $API_KEY" \
  -d '{"contents":[{"parts":[{"text":"你好"}]}]}'
```

### Responses API 端点

```bash
POST /v1/responses                    # 非流式
POST /v1/responses                    # 流式 (Accept: text/event-stream)
```

请求/响应格式与 OpenAI Responses API 一致，支持 Codex 工具兼容。

### 管理端点

`/admin` 提供配置管理 UI 与 REST API（后端/协议映射/模型映射 CRUD），详见下方[配置](#配置)章节。默认端口 8493，登录账号见[启动](#启动)。

## 快速开始

### 前置要求

- JDK 25+
- Maven 3.8+

### 编译与测试

```bash
mvn clean compile -DskipTests
mvn test
```

### 启动

```bash
# 管理界面账号（默认 admin / 123456）
set ADMIN_USERNAME=admin              # Windows
set ADMIN_PASSWORD=your-password     # Windows
export ADMIN_USERNAME=admin           # Linux/macOS
export ADMIN_PASSWORD=your-password  # Linux/macOS

mvn clean package -DskipTests
java -jar target/llmlinkj-0.5.0.jar
```

启动后访问 `http://localhost:8493/admin` 配置后端（API Key、base-url 等），无需重启。

### Docker

```bash
docker build -t llmlinkj .
docker run -p 8493:8493 -e ADMIN_PASSWORD=your-password llmlinkj
```

## 配置

配置不在 yml，持久化在 SQLite（`config.db`，WAL 模式），通过 `/admin` 管理 API 在线维护，无需重启。

#### 三张核心表

| 表 | 作用 |
|------|------|
| `backend_config` | 后端连接：protocol + base-url + api-key + default-model + 超时/连接池 |
| `protocol_mapping` | 入站协议 → 目标后端 + enabled 开关 |
| `model_mapping` | 请求模型 → 实际模型映射（复合主键 client_protocol + backend_cfg_name + request_model） |

schema 由 Flyway 迁移管理（`src/main/resources/db/migration/V1__init.sql` ~ `V6__seed_data.sql`）。

#### 管理 API（`/admin`，需登录）

- `GET/POST/DELETE /api/backends[/{name}]` — 后端配置 CRUD
- `GET/POST/DELETE /api/protocols[...]` — 协议映射 CRUD
- `POST/DELETE /api/protocols/{cp}/{bcn}/model-mappings[/{requestModel}]` — 模型映射 CRUD

访问 `/admin` 进入管理 UI（默认账号 admin / 123456，可用 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 环境变量覆盖）。

### 路由决策

`ProxyOrchestrator.resolve(inboundProtocol, requestedModel)` 三步（DB 驱动）：

```
1. 查 protocol_mapping → 取入站协议下最新 enabled 记录（含目标后端名 backendCfgName）
2. 查 backend_config → 拿 defaultModel + protocol
3. 查 model_mapping → 取 actualModel（未命中用 defaultModel，再不行用 requestedModel）
```

| 入站协议 | model 值 | 路由到 | 实际模型 |
|----------|----------|--------|----------|
| openai | `deepseek-v4-flash` | deepseek | `deepseek-v4-flash` |
| gemini | `gemini-2.0-flash-exp` | deepseek | `deepseek-v4-flash`（model_mapping 命中）|
| openai | `some-model` | protocol_mapping 指定后端 | `some-model`（无 mapping 且无 defaultModel 时直传）|

入站协议无任何 enabled 的 protocol_mapping 时报错（无 default-backend 兜底）。

### 熔断与限流

基于 Resilience4j，按后端实例名配置：

| 策略 | 实例名 | 关键参数 |
|------|--------|----------|
| 熔断 | `deepseek-api` / `gemini-api` | 窗口 10 次，失败阈值 50%，30s 后半开 |
| 重试 | `deepseek-api` / `gemini-api` | 最多 2 次，间隔 2s |
| 限流 | `deepseek-api` / `gemini-api` | 每秒 50 次，排队 5s |
| 超时 | `deepseek-api` / `gemini-api` | 120s |

## 项目结构

```
src/main/java/com/ai8493/llmproxy/
├── adapter/
│   ├── ProtocolAdapter.java       # 外部协议 ↔ IR
│   ├── BackendAdapter.java        # IR ↔ 后端 SDK
│   ├── anthropic/                 # Anthropic 协议 + 后端适配
│   ├── gemini/                    # Gemini 协议 + 后端适配
│   └── openai/                    # OpenAI 协议适配 + 后端适配（含 Responses）
├── cache/                         # 会话级 reasoning 缓存
├── client/                        # 后端 HTTP 客户端工厂
├── clients/                       # 客户端配置文件管理（Codex / Gemini CLI）
├── config/                        # 配置持久化（entity + repository + ConfigService）
├── controller/                    # HTTP 端点
├── converter/                     # Tool/FunctionCall 映射器
├── exception/                     # 全局异常处理 + 异常类
├── filter/                        # TraceId / 请求响应日志过滤器
├── metrics/                       # Micrometer 指标
├── model/                         # 14 个 IR record/sealed interface
├── orchestrator/                  # 核心调度器（路由 + 模型映射）
└── util/                          # 脱敏工具（API Key 仅显后 4 位）
```

## 扩展

### 新增客户端协议

实现 `ProtocolAdapter` 接口 → 在 Controller 加端点 → 完成。

### 新增后端（以 DeepSeek 为例）

1. 实现 `BackendAdapter`：IR ↔ DeepSeek SDK
2. 在 `BackendAdapterFactory` 的 switch 中加分支
3. 通过 `/api/backends`（POST）写入一条 BackendConfig（指定 protocol 字段）
4. 通过 `/api/protocols` 添加入站协议到该后端的映射
5. 可选：通过 `/api/protocols/{cp}/{bcn}/model-mappings` 加模型别名

Controller、协议转换、路由调度全部复用，无需改动。

## 技术栈

| 组件 | 版本 |
|------|------|
| JDK | 25 |
| Spring Boot WebFlux | 3.5.14 |
| OpenAI Java SDK | 4.35.0 |
| Google GenAI Java SDK | 1.53.0 |
| Anthropic Java SDK | 2.33.0 |
| OkHttp | 4.12.0 |
| Resilience4j | 2.3.0 |
| Micrometer | 1.15.11 |
| JUnit 5 + AssertJ + WireMock | 测试 |

## 安全

- API Key 通过 `/admin` 管理 API 写入 DB，代码/配置/日志中禁止明文
- 日志中 API Key 仅显示后 4 位
- 管理端点 `/admin/**` 需 form 登录（默认 admin / 123456，可用 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 覆盖）
- 代理 API（`/v1/**`、`/v1beta/**`）不走 Spring Security，认证由 API Key 负责
- CSRF 仅对 `/admin/**` 非安全方法校验
- SSRF 防护：**当前已失效**——移除 proxy yml 配置时未恢复 `BackendUrlValidator`（见 `docs/superpowers/plans/2026-06-26-remove-proxy-yml-config.md`）

## 许可证

Apache 2.0
