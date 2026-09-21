# ADR-003：Agent Runtime 语义不变量

- 状态：已接受
- 日期：2026-09-13

## 背景

Phase 4 建立了 Agent Runtime Core 的事件契约与 Decision Trace；Phase 5 在 Run、模型调用和已获准的工具执行周围加入同步、透明的扩展内核。P5H 冻结两者组合后的语义：Core 工作之前或之后发生失败、模型一次返回多个工具调用、模型循环达到轮次上限时，事件仍必须反映真实发生的事实。

本 ADR 记录当前实现及测试锁定的行为，不新增持久化执行、恢复、重试、取消或工具并行执行。

## 决策

### 1. Run 终态

Agent Runtime Core 一旦开始，就会在第 `0` 轮发出 `RUN_STARTED`，并且恰好产生以下终态之一：

- 最终模型轮次为 `STOP` 时，发出 `RUN_COMPLETED`；
- 发生错误、Policy 拒绝或达到最大轮次时，发出 `RUN_STOPPED`；
- 决策为 `REQUIRE_APPROVAL` 时，发出 `RUN_WAITING_APPROVAL`。

终态事件是最后一个 Core 事件。`RUN_WAITING_APPROVAL` 是 Phase 4 已冻结的暂停终态，也是简化表述“`RUN_COMPLETED XOR RUN_STOPPED`”的明确例外。如果 `aroundRun` 在继续执行前失败，Core 根本没有启动，因此不发出 Core 事件；如果它在 Core 已完成后失败，Harness 传播扩展失败，不新增或改写 Core 终态事件。

### 2. 模型调用终态

每一次真正开始的模型调用都恰好有一个终结事实：

```text
MODEL_STARTED -> MODEL_COMPLETED
MODEL_STARTED -> MODEL_FAILED
```

模型 Middleware 在 `proceed()` 前失败时，没有模型事件，Run 以 `INTERNAL_ERROR` 停止。真实模型失败会产生 `MODEL_FAILED` 并以 `MODEL_ERROR` 停止，即使模型抛出的异常类名看起来像 Middleware 异常。模型 Middleware 在 `MODEL_COMPLETED` 之后失败时，保留完成事实，Run 以 `INTERNAL_ERROR` 停止；不得追加 `MODEL_FAILED`。

`STOP` 完成态声明零个 ToolCall；`TOOL_CALLS` 完成态至少声明一个，声明数量必须等于该轮实际出现的 `TOOL_REQUESTED` 数量。唯一的数量不匹配例外是：模型调用完成后、Runner 尚未来得及发布请求时，模型 Middleware 立即失败。

### 3. 工具执行终态

受治理的执行链保持为：

```text
resolve -> deserialize -> validate -> policy -> ALLOW
        -> aroundToolExecution -> TOOL_STARTED
        -> execute -> serialize -> TOOL_SUCCEEDED / TOOL_FAILED
```

未知工具、非法参数、`DENY` 与 `REQUIRE_APPROVAL` 都不会进入工具 Middleware，也不会发出 `TOOL_STARTED`。一旦发出 `TOOL_STARTED`，恰好由 `TOOL_SUCCEEDED` 或 `TOOL_FAILED` 之一记录其 Core 结果。

工具 Middleware 在 `proceed()` 前失败，发生于 `ALLOW` 之后、`TOOL_STARTED` 之前，Run 以 `INTERNAL_ERROR` 停止。真实工具失败产生 `TOOL_FAILED`，Run 以 `TOOL_ERROR` 停止。工具 Middleware 在 `TOOL_SUCCEEDED` 后失败时，保留成功事实，Run 以 `INTERNAL_ERROR` 停止；绝不将成功改写为 `TOOL_FAILED`。

### 4. 事件顺序与非法轨迹

事件序号从 `1` 开始，在单个 Run 内连续，且所有事件共享同一个 `runId`。模型轮次从 `1` 开始逐轮递增。工具生命周期事件通过 `runId`、轮次、工具名和 Run 内唯一的 `toolCallId` 关联。

Decision Trace 会拒绝的非法历史包括：

- 第二次或非起始位置的 `RUN_STARTED`、缺失终态、多个终态、终态后的事件；
- 没有对应 Started 的模型完成或失败、同一次模型调用出现两个终态、模型失败后继续模型工作；
- `STOP` 轮次之后出现工具请求，或实际请求数与模型声明数不同；
- 验证、Policy、开始、成功、失败以不可能的顺序发生；
- Run 停止原因与紧邻的模型、工具或 Policy 原因矛盾；
- 没有最终 `MODEL_COMPLETED(STOP)` 却出现 `RUN_COMPLETED`。

### 5. 多工具顺序

同一个 `ModelTurn` 中的 ToolCall 保留模型给出的顺序。Runtime 先按顺序发布所有 `TOOL_REQUESTED`，再以相同顺序同步、串行执行。工具 A 到达其终态后，B 才能开始；B 到达终态后，C 才能开始。追加到下一次模型请求中的 ToolResult 消息也保持该顺序。

执行采用失败即停。若 A 成功、B 失败、C 尚未开始：

```text
A -> TOOL_SUCCEEDED
B -> TOOL_FAILED
C -> 已请求，但 NOT_STARTED
Run -> RUN_STOPPED(TOOL_ERROR)
```

C 不会执行。此前 A 的成功在事件和 Decision Trace 中都仍是成功。

### 6. 部分执行事实

父级失败绝不能改写已完成的子级事实。后续工具失败不会改变先前的 `MODEL_COMPLETED` 或 `TOOL_SUCCEEDED`。同理，Middleware 后置阶段失败可以改变 Run 或 Harness 结果，但不能在模型或工具成功后伪造对应的失败事实。

### 7. 轮次计数

一个 iteration 是从 `1` 开始编号的模型循环槽位。`AgentRunResult.iterations` 统计真正开始的模型调用：

- 模型 Middleware 在 `proceed()` 前失败，不计入已开始次数，报告此前的计数（第一个槽位为 `0`）；但 `RUN_STOPPED` 携带尝试执行的槽位；
- 真实模型失败计入已开始调用；
- 模型完成后 Middleware 失败计入该调用，并保留其 Token Usage；
- 工具及工具 Middleware 的结果属于请求它们的模型轮次。

### 8. 最大轮次语义

`maxIterations` 是真实模型调用槽位的最大数量，不是 ToolCall 的最大数量。循环包含从 `1` 到 `maxIterations` 的所有允许槽位；`1`、`2`、`3` 的测试证明不会多调用一次。如果最后一个允许的模型轮次请求工具，这些工具照常处理，随后 Run 以 `MAX_ITERATIONS` 停止，不再启动新模型调用。

### 9. 模型输出的身份与有效性

`ModelTurn` 拒绝互相矛盾的 `STOP + tool calls`（最终回答却带工具调用）和 `TOOL_CALLS + empty calls`（声明工具调用却没有调用）状态。构造不可变列表时会拒绝 null 工具元素，也会拒绝同一轮中重复的 ToolCall ID。`AgentRunner` 还会拒绝同一 Run 后续轮次复用 ToolCall ID；在发布 `MODEL_COMPLETED` 前将这种非法模型输出归类为 `MODEL_ERROR`。这使审批查找和事件关联保持无歧义。

对 `STOP` 轮次，null ToolCall 列表会规范化为空的不可变列表；null content 和 Token Usage 保持既有的规范化语义。

### 10. Middleware 与 Core 失败的区分

失败按来源分类：

```text
Middleware 失败 -> INTERNAL_ERROR
真实模型失败     -> MODEL_ERROR
真实工具失败     -> TOOL_ERROR
```

透明 Middleware 仍须同步、限定于调用作用域和当前线程、恰好调用一次下游，并保持相同的结果对象身份。P5H 不扩展这一契约。

## 后果

- Runtime 事件流与 Decision Trace 有确定的解释方式。
- 审批和工具事件关联依赖整个 Run 内唯一的 ToolCall ID。
- 多工具处理确定、有序、失败即停，同时保留部分执行事实。
- 轮次上限可以作为模型调用次数的严格上界来测试。
- Core 事实与外层 Harness 结果刻意保持分离。

## 暂缓事项

未来阶段可以定义持久化的 `UNKNOWN` 结果、持久化投影、Checkpoint / Resume、真实取消、重试、幂等、锁、限流、RAG / 记忆、异步 Middleware 或工具并行执行。这些能力必须保留上述事实与顺序规则；P5H 均未实现。
