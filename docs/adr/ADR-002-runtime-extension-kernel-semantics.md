# ADR-002：Runtime 扩展内核的语义边界

- 状态：已接受
- 日期：2026-09-13

## 背景

Phase 5 在 Agent Run、模型调用和已获准的工具执行周围加入可复用扩展边界。它支持可观测性，但不能演变成第二套 Runtime、削弱工具治理，或改写已经发生的 Core 事实。

## 决策

### 1. Middleware 是透明扩展

`RuntimeMiddleware` 必须恰好调用一次 `next.proceed()`，并原样返回下游的同一个结果对象实例。它不能短路执行、替换结果或改变 Agent Core 语义。指标、追踪、日志、延迟与 Token 观测，以及上下文补充，属于这一边界。

`next` 是同步能力，只在对应 Middleware 调用的动态作用域和当前线程内有效。Middleware 返回、验证失败或异常离开调用时，扩展内核都会关闭它。被保存或跨线程传递的 `next` 不能在边界关闭后再启动下游工作。

请求幂等、Session Lock 拒绝、准入控制、限流拒绝、取消预检和缓存结果返回，需要未来独立的 Execution Guard / Preflight 边界，不属于透明 Middleware。

### 2. Core 结果与 Harness 结果不同

Agent Event 描述 Agent Runtime Core 的生命周期，而不是应用层或 Harness 的每一次后处理失败。一旦发出 `RUN_COMPLETED`，`aroundRun` 后置阶段的失败就是 Harness / 扩展层失败。它应传播给调用方，但不得再发出 `RUN_STOPPED`、改写已完成的 Core 结果，或制造第二个终态事件。

### 3. 按失败来源分类

`RuntimeMiddlewareFailureException` 标识扩展内核明确判定为 Middleware 来源的失败。Middleware 链会原样重新抛出下游模型或工具异常。因此，即使模型或工具抛出的异常类名恰好是 `RuntimeMiddlewareException`，仍分别归类为 `MODEL_ERROR` 或 `TOOL_ERROR`；异常名称碰撞不会把它变为 `INTERNAL_ERROR`。

### 4. 工具治理先于 Middleware

只有在工具查找、反序列化、参数验证、Policy 评估完成且决策为 `ALLOW` 后，才会进入工具 Middleware。未知工具、非法参数、`DENY` 与 `REQUIRE_APPROVAL` 都不能进入工具 Middleware 链。Middleware 不是绕过安全控制的通道。

### 5. 外部副作用事实优先于后续失败

一旦发出 `TOOL_SUCCEEDED`，后续 Middleware 或 Harness 失败都不能把该工具重新解释为失败。因此，Decision Trace 可以如实同时包含“工具成功”和“Run 因 `INTERNAL_ERROR` 停止”。

未来持久化必须区分三类事实：

- 工具调用事实（Tool Invocation Truth）：外部副作用是否发生。
- 工具结果处理事实（Tool Result Processing Truth）：结果是否完成序列化和处理。
- Run 事实（Run Truth）：整个 Agent Run 是否完成。

当前同步执行器先调用外部工具，再序列化结果。因此，非幂等副作用可能已经成功，而序列化随后失败。未来的持久化集成必须以持久化的 `UNKNOWN` 语义表达这种不确定性，绝不能盲目重试非幂等操作。Phase 5 明确不实现持久化工具执行、恢复、重试、Outbox 或 Checkpoint。

### 6. 取消只做上下文传递

`CancellationContext` 是上下文传递的扩展点。Phase 5 不定义线程中断、取消事件、Redis 标记、线程取消或工具取消语义。

## 后果

- `AgentRunner` 仍是 Model-to-Tool 推理循环的唯一负责人。
- `ToolExecutor` 仍负责工具契约验证与 Policy 执行。
- `AgentExecutionCoordinator` 创建单个 Run Context 并应用 `aroundRun`；它不重复模型、Policy、工具、事件、持久化或恢复逻辑。
- Middleware 实例及其链可以跨 Run 共享；每次 Run 的可变状态应放在 `AgentRuntimeContext.attributes()`。
- 生产环境的 `requestId` 强制校验留待幂等 Guard 接入；最小运行和测试可以不提供它。
