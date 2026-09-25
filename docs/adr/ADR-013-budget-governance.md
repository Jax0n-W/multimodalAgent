# ADR-013：运行级预算治理

## 状态

P9.2 已实现；本 ADR 冻结单次 Agent Run 的执行预算语义，不引入配置快照、评测、恢复、分布式配额或计费系统。

## 决策

预算是 Runtime Core 内显式的 execution guard，不是 `RuntimeMiddleware`。Middleware 继续保持透明、同步且恰好一次调用 `next.proceed()`；预算 admission 不使用 middleware short-circuit。

不可变的 `ExecutionBudget` 作为 `AgentRunSpec` 的显式配置，支持可选的 Model 调用次数、Tool 调用次数、输入 token、输出 token、总 token 和成本上限。全部限制缺省时等价于 `ExecutionBudget.unlimited()`。`AgentRunSpec.maxIterations` 继续限制 Runtime 循环，`MAX_ITERATIONS` 与 `BUDGET_EXHAUSTED` 是不同的 terminal truth。

每个 Run 创建独立的 mutable `BudgetSession`，只在内存中记录真正跨过 execution-start boundary 的 Model/Tool 次数、Provider 已确认的 token 使用量和可以可靠计算的成本。该 session 不跨 Run 共享，也不使用 Redis 或 JPA 作为实时计数器；P10 之前不承诺预算恢复。

Budget admission 只预留开始工作的许可；execution counter 表示已经跨过执行开始边界的真实 work。Model/Tool middleware 在调用 downstream 之前失败，不消耗对应执行预算。downstream 已经开始后，无论 Provider/Tool 失败还是 middleware 在返回阶段失败，都不得退还预算；已经建立的 `MODEL_COMPLETED`、`TOOL_SUCCEEDED` 等事实也保持不变。

Model 调用次数是严格的 pre-admission limit：先执行现有 `BEFORE_MODEL` cancellation checkpoint，再检查并预占一次调用，之后才允许 `MODEL_STARTED` 和 Provider 调用。已经 admission 的调用即使以 `MODEL_ERROR` 或 `MODEL_TIMEOUT` 结束也已消耗预算，且其真实失败不能被预算结果改写。

Tool 调用次数同样是严格的 pre-admission limit，但只计入已经通过反序列化、Validation 和 Policy `ALLOW` 的执行。顺序固定为：

```text
TOOL_REQUESTED
→ validation
→ policy ALLOW
→ BEFORE_TOOL_EXECUTION cancellation checkpoint
→ budget admission
→ TOOL_STARTED
→ side effect
```

Validation failure、`DENY` 和 `REQUIRE_APPROVAL` 不消耗 Tool 调用预算。已经 `TOOL_STARTED` 的执行即使失败也已消耗预算。预算阻断不会产生 `TOOL_STARTED`、不会调用 Tool，也不会伪装成 `TOOL_ERROR`。

Token 与 Cost 是 confirmed-consumption budget，不声称在 Provider 调用之前预测最终使用量。只有 `MODEL_COMPLETED` 后才将 `TokenUsage` 记入 session；达到或超过上限只能阻止后续新的 Model/Tool work，不能回滚已经完成的调用。已经返回 `STOP` 的完整答案仍为 `COMPLETED`，已经返回 `LENGTH` 的结果仍为 `MODEL_OUTPUT_LIMIT`，二者都不能被事后改写成预算停止。

`UNKNOWN_OR_INCOMPLETE` usage 绝不当作零。如果未启用 token budget，保持既有行为；如果启用了 token budget，而且 Runtime 还要开始未来工作，则 fail closed 为 `BUDGET_UNVERIFIABLE`。成本只在显式 `ModelPricing` 与完整 usage 同时存在且模型身份匹配时使用 `BigDecimal` 计算。禁止硬编码 Provider 当前价格，也禁止把本地 Ollama 推断为零成本。配置 `maxCost` 但价格或权威 usage 不可用时，在下一项受治理工作之前以 `BUDGET_UNVERIFIABLE` 阻断。

预算阻断产生结构化 `BudgetBlockedEvent`，携带 `BudgetDimension`、`BudgetBlockReason`、limit、可用时的 observed value，以及可选 Tool correlation。事件顺序固定为：

```text
BudgetBlockedEvent
→ RunStoppedEvent(BUDGET_EXHAUSTED | BUDGET_UNVERIFIABLE)
```

`DecisionTraceBuilder` 只接受匹配的预算事件作为上述 stop reason 的直接原因；`TOOL_POLICY_EVALUATED(ALLOW) → BudgetBlockedEvent → RUN_STOPPED` 是合法的未启动 Tool terminal path。P6 durable projection 接受并持久化预算事件产生的事实和最终 stop reason，但 P9.2 不新增预算表。

既有 cancellation precedence 不变：同一 Model 或 Tool admission boundary 已经观察到 cancel 时，结果必须是 `CANCELLED`，预算不得覆盖它。

生产配置位于 `multimodal-agent.runtime.budget.*`，全部 unset 时保持 unlimited。`ai.max-tokens` 是单次 Provider generation 的输出限制；Runtime `max-output-tokens` 是 Run 级已确认输出 token 预算，二者不能混用。

## 架构边界

预算领域只依赖 Provider 中立的 `ModelIdentity`、`TokenUsage` 和显式 `ModelPricing`。Runtime Core 继续不依赖 Spring AI、WebClient、Reactor、Redis、JPA 或具体 Provider SDK。

## 暂缓事项

P9.3 configuration snapshot、P9.4 eval、P10 checkpoint/recovery，以及分布式 quota、Redis budget counter、限流、订阅套餐、计费、Provider 路由/降级、预算预测与旧 ChatService 迁移均不属于 P9.2。
