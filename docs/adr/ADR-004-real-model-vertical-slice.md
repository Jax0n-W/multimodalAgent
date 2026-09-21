# ADR-004：真实模型纵向闭环

- 状态：已接受；已通过 Runtime 端到端验证
- 日期：2026-09-13

## 背景

P5V 通过 Spring AI 和 Ollama，将本地微调模型 `mindbridge-qwen2.5-7b-ft` 接入已冻结的 Java Agent Runtime。该模型是 Q4_K_M 量化的 Qwen2.5-7B GGUF，在 Ollama 中注册为 `mindbridge-qwen2.5-7b-ft:latest`。

本阶段必须证明非流式链路：从 `AgentExecutionCoordinator` 出发，经过真实模型产生 ToolCall、受治理的 Java 工具执行，再回到模型的最终回答。本阶段不迁移生产 `ChatService`，也不增加平台能力。

## 决策

### 1. Runtime 不依赖模型提供方

Runtime 契约不包含 Spring AI、Ollama 或 OpenAI 类型。`AgentModelRequest` 携带不可变 Runtime 消息和与提供方无关的 `ModelToolDefinition`。

### 2. Adapter 是模型提供方集成边界

`SpringAiOllamaAgentModelAdapter` 负责 Spring AI 请求/响应映射、结束原因处理、JSON 参数语法解析和 Token Usage 提取。可选的 `SpringAiOpenAiAgentModelAdapter` 复用相同的协议映射，不改变 Runtime。

### 3. 通过本地 Ollama 的 OpenAI 兼容接口通信

实际提供方仍是 `http://127.0.0.1:11434` 的本地 Ollama，实际模型仍是 `mindbridge-qwen2.5-7b-ft`。Adapter 使用配置为该本地地址的 Spring AI `OpenAiChatModel`；不调用 OpenAI 服务，也不使用 OpenAI 凭证。

选择这一传输方式的原因是：Spring AI 1.0.0 的原生 `OllamaChatModel` 将 Ollama 原生 ToolCall 映射为 Spring AI ToolCall 时，会得到空 ID。非空的提供方 ToolCall ID 是已冻结的 Runtime 不变量，用于审批、事件关联、整个 Run 内的唯一性以及第二次模型调用时的 ToolResult 关联。Ollama 的 OpenAI 兼容接口保留该 ID。P5V 不升级 Spring AI。

### 4. Spring AI 不执行工具

Spring AI 仅接收定义用途的 `ToolCallback`，其内部工具执行被显式禁用。若回调意外被调用，会直接抛出异常，按封闭原则失败。

### 5. `ToolExecutor` 是唯一工具执行路径

提供方的 ToolCall 被映射为 Runtime `ToolCall`，继续走既有链路：

```text
ToolRegistry -> Deserialize -> Validation -> Policy -> RuntimeMiddleware -> Tool.execute
```

### 6. 只向模型暴露当前 Run 的 `allowedTools`

每次调用模型前，`AgentRunner` 都会取已注册工具描述与 `AgentRunSpec.allowedTools` 的交集。这是最小权限投影，但不能替代 `ToolExecutor` 的 Policy 校验。

### 7. 保留提供方 ToolCall 身份及顺序

提供方 ToolCall ID、工具名和调用顺序原样复制到 Runtime。缺失 ID、不支持的调用类型或畸形协议数据在模型边界失败。`AgentRunner` 继续保证 ToolCall ID 在整个 Run 中唯一。

### 8. 语法解析与语义验证分离

Adapter 仅把提供方参数 JSON 解析为 `Map<String, Object>`，不执行 Bean Validation 或业务规则。强类型映射、Jakarta Validation 和 Policy 仍由 `ToolExecutor` 负责。

### 9. Token Usage 映射

提供方的 Prompt 与 Completion Token 数量分别映射为 Runtime 输入与输出 Token。没有 Usage 时明确回退为 `TokenUsage.ZERO`。预算和配额执行留待后续阶段。

### 10. 不支持的提供方结果视为模型失败

只接受无 ToolCall 的 `STOP` 与有 ToolCall 的 `TOOL_CALLS`。传输错误、缺失或畸形响应、不支持的调用类型、畸形参数和不支持的结束原因都会从 Adapter 抛出。`AgentRunner` 将其转化为 `MODEL_FAILED`，随后发出 `RUN_STOPPED(MODEL_ERROR)`。

### 11. 真实模型测试需要显式启用

标记为 `real-model` 的测试默认不由 Maven 执行，仅在 `real-model` profile 中启用。测试先查询 Ollama `/api/tags`；端点或模型不可用时，会明确说明原因并跳过。测试绝不启动、停止或重新配置 Ollama。

默认值：

```text
OLLAMA_BASE_URL=http://127.0.0.1:11434
OLLAMA_MODEL=mindbridge-qwen2.5-7b-ft
```

无需外部 API Key。

### 12. 从 `AgentExecutionCoordinator` 验证

两条真实 Smoke Test 均从 `AgentExecutionCoordinator` 进入，而非直接调用 Adapter：

1. 直接回答：一次真实模型调用、`STOP`、非空回答、`RUN_COMPLETED`，没有工具执行。
2. 工具往返：真实 ToolCall，经 `ToolExecutor` 确定性执行一次 `knowledge_search`，通过提供方 ID 关联 ToolResult，第二次真实模型调用后得到 `STOP`、非空回答和最终 `RUN_COMPLETED`。

第二条测试会记录模型请求和模型轮次，以证明第二次调用同时包含 Assistant ToolCall 及匹配的 ToolResult。

### 13. P5V 不采用流式调用

每个 Runtime 模型轮次通过 Spring AI `ChatModel.call` 进行一次同步调用。流式与部分 ToolCall 协议留待后续。

### 14. P5V 不集成持久化或生产 `ChatService`

本阶段没有连接 AgentRun、AgentStep、ToolExecution、Checkpoint 或恢复持久化。现有 `ChatService`、Controller、SSE 流程、记忆、RAG 和旧 `AiClient` 都不变。

## 后果

- 本地微调 Ollama 模型能够提出工具调用，但不能绕过 Runtime 治理。
- 提供方 ToolCall ID 在“模型 → Runtime → 模型”的全链路中保留。
- 工具 Schema 在 Adapter 边界从 Java 输入类型生成。
- 默认 CI 保持确定性，不调用真实模型网络。
- 确定性 Adapter 测试通过本身不足以证明真实纵向闭环；两条 Coordinator 级真实 Smoke Test 通过后，P5V 才冻结。

## 验证记录

2026-09-13，`mvn -Preal-model -Dtest=RealModelAgentSmokeTest test` 针对本地 Ollama 模型运行，两条测试均通过且没有跳过：

- 直接回答：一次模型调用、`STOP`、非空回答、最终 `RUN_COMPLETED`，无工具。
- 工具往返：首轮为 `TOOL_CALLS`，`ToolExecutor` 执行一次 `knowledge_search`；提供方 ID 保留在 Assistant ToolCall 与 ToolResult 中；次轮为 `STOP`、非空回答、最终 `RUN_COMPLETED`。

Runtime 端到端测试之前，已经独立验证本地模型原生 Tool Calling 和 ToolResult 后续处理行为。Coordinator 测试是 P5V 的冻结证据。

## 暂缓事项

P6 持久化集成、P7 Redis 协调、P8 流式、P9 Gateway / Budget、P10 恢复、P11 记忆、P12 RAG 和 P13 生产加固均不在本阶段范围内。
