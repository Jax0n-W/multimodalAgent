# ADR-012：模型网关

## 状态

P9.1 已实现；本 ADR 冻结模型调用的统一治理边界，不引入预算、快照或评测系统。

## 决策

`AgentModel` 继续作为 Provider 中立的模型适配端口，负责把 Runtime 的 `AgentModelRequest` 转换为具体 Provider 请求，并返回一个完整的 `ModelTurn`。生产调用链调整为：

```text
AgentRunner
  → ModelGateway
  → AgentModel
  → OpenAiCompatibleStreamingAgentModelAdapter
  → Provider
```

`ModelGateway` 是 Provider 中立的模型治理边界，统一持有 `ModelIdentity(provider, model)`、`ModelTimeoutPolicy(invocationTimeout, idleTimeout)`、失败分类和调用 telemetry。它不依赖 Spring AI、WebClient、Reactor 或具体 Provider SDK。流式超时的实际取消由支持超时策略的 Adapter 使用 Reactor 完成；Gateway 负责下发同一策略并把 Adapter 的结构化失败转换为稳定的 `ModelInvocationException`。不使用线程中断或 `Future.cancel(true)`。

模型失败采用有限且稳定的分类：`TIMEOUT`、`RATE_LIMITED`、`PROVIDER_UNAVAILABLE`、`INVALID_REQUEST`、`CONTEXT_TOO_LARGE`、`MALFORMED_RESPONSE`、`PROVIDER_ERROR`。Adapter 必须显式提供能够可靠判断的类别，禁止解析异常 message 猜测；无法可靠分类时使用 `PROVIDER_ERROR`。Runtime Core 只将 `TIMEOUT` 映射为 `MODEL_TIMEOUT`，其他模型失败映射为 `MODEL_ERROR`，并始终保留真实的 `MODEL_FAILED` lifecycle fact。

超时分为两类：`invocationTimeout` 限制一次完整模型调用的总时长，`idleTimeout` 限制流式响应连续无新事件的时长。流式 Adapter 仍然先聚合 Provider chunks，再产生唯一完整 `ModelTurn`；部分文本和部分 ToolCall 不会越过模型边界进入 ToolExecutor。因此超时不会执行不完整的 ToolCall，也不改变 P8 的 cooperative cancellation、ModelDelta、ToolCall ID 或 streamSequence 语义。

`TokenUsage` 明确区分 `KNOWN` 与 `UNKNOWN_OR_INCOMPLETE`。真实的零 token 使用量可以表示为已知零值；Provider 未返回 usage 时必须使用 unknown，不能伪装成零。跨 invocation 聚合时，只要任一 usage 未知，Run 聚合结果就保持 incomplete，即使仍保留其他 invocation 已知的计数。

每次经过 Gateway 的调用通过小型 `ModelInvocationTelemetrySink` 观测：`invocationId`、模型身份、iteration、latency、finish reason、token usage 或 failure kind。默认 sink 为 NOOP；telemetry sink 失败只影响观测，不能改变模型调用结果。P9.1 不建立 metrics 数据库，也不计算价格。本地 Ollama 不被声明为零成本；未来只有在显式价格配置且 usage 完整时才能计算成本。

现有 `RuntimeMiddleware` 仍保持透明、恰好一次调用 `next.proceed()` 的冻结语义。模型网关不是 middleware，不利用 middleware 做 admission、short-circuit 或 timeout。

## 架构边界

Runtime Core 只依赖 Provider 中立的 Gateway contract 和模型数据类型，不依赖 Spring AI、WebClient、Reactor、JPA 或 Redis。Provider/框架相关超时执行与响应解析留在 Adapter 层，生产装配负责确保 `AgentRunner` 获取的是 `ModelGateway` 而不是裸 Adapter。

## 暂缓事项

预算与成本策略、模型快照、评测、Provider 路由、fallback、负载均衡、多 Provider DSL、旧 `ChatService` / `AgenticRagService` 迁移分别留给 P9.2、P9.3、P9.4 或后续阶段。
