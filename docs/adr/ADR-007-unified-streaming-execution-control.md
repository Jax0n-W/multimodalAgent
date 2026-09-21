# ADR-007：统一流式执行与执行控制契约

- 状态：P8.1 契约基础已接受
- 日期：2026-09-17

## 背景

已冻结的 Runtime Core 通过 `AgentEvent` 产生权威的 Model、Tool 和 Run 事实，并返回一个终态 `AgentRunResult`。P6 将这些事实投影为持久历史；P7 用 Redis 租约保护临时活跃所有权。但这些契约尚未提供面向客户端的统一实时流，也没有定义请求取消的语义。

P8 最终需要支持：

```text
Runtime -> 实时执行流 -> 客户端
客户端 -> 执行控制意图 -> Runtime
```

P8.1 只定义语义基础与类型契约，不集成流式模型提供方、不创建 SSE 端点或 StreamHub、不把取消检查点接入 Runtime，也不实现分布式控制。

## 问题

模型输出片段、Runtime 事实和控制观测必须呈现在同一个有序实时投影中，但该投影不能成为另一套执行状态机。取消必须可以表达，同时不能中断已开始的操作、绕过 Tool Governance、改写终态事实，或混淆 P6/P7 的失败语义。

依赖方向也必须保留：Runtime 可以理解通用执行控制语义，但不能知道 HTTP、SSE、WebFlux、Reactor、Redis、Pub/Sub、Controller、持久化 Adapter 或节点身份。

## 决策

### 权威边界

五类权威保持分离：

```text
Runtime Core
    = Model / Tool / Run 的执行事实

MySQL
    = 成功持久化的历史 + 持久化 Run 身份

Redis Run Lease
    = 临时活跃执行所有权

Unified Execution Stream
    = 仅实时观测与投影

Execution Control
    = 请求未来执行行为的意图
```

流消息不能决定 Runtime 是否成功或失败。取消标记不能直接改变持久化 Run 状态。Redis 控制状态不能成为 Runtime 状态机。SSE 连接不能拥有执行生命周期。

### 实时投影信封

`ExecutionStreamEvent` 是不可变信封，包含：

```text
runId
streamSequence
occurredAt
kind
强类型 payload
```

允许的 `kind` 为 `RUNTIME_EVENT`、`MODEL_DELTA`、`CONTROL_EVENT`。Payload 构成 sealed 层级：

- `RuntimeEventPayload` 保留原始 `AgentEvent` 对象，不复制或修改；
- `ModelDelta` 表示观测到的提供方文本片段；
- `ControlEvent` 表示观测到的执行控制状态。

信封拒绝缺失身份、非正数序号、缺失时间戳、缺失 kind 或 payload、kind/payload 不匹配，以及包裹了不同 `runId` 的 Runtime 事件。不使用 `Map<String, Object>` 或无类型 Payload。

对于 `RUNTIME_EVENT`，包裹的事实保留其原始 `occurredAt`；对于 Model 和 Control 观测，信封 `occurredAt` 是未来实时 Publisher 提供的观测时间。时间戳不构成第二种排序权威；顺序由 `streamSequence` 决定。

Stream 包位于 `agent.runtime` 之外，可以向内依赖 Runtime 事实和通用控制类型。Runtime 不得向外依赖实时流契约。

### Runtime 事件序号与实时流序号

`AgentEvent.sequence` 仍表示权威 Runtime Core 事实的顺序，保持不变，且不包含 Model Delta 或 Control 观测。

`ExecutionStreamEvent.streamSequence` 对同一 `runId` 的所有实时观测排序，例如：

```text
streamSequence=1  RUNTIME_EVENT  RUN_STARTED
streamSequence=2  RUNTIME_EVENT  MODEL_STARTED
streamSequence=3  MODEL_DELTA    "Hello"
streamSequence=4  MODEL_DELTA    " world"
streamSequence=5  RUNTIME_EVENT  MODEL_COMPLETED
```

**一个 `runId` 的一条实时执行流，恰好只有一个 `streamSequence` 分配权威。`RuntimeEvent`、`ModelDelta` 和 `ControlEvent` 的各个生产者不得拥有独立的序号空间。**

这个 Run 级权威为每个信封分配序号，不论观测来自 Runtime 事实、模型提供方还是控制边界。共享序号从 1 开始，严格递增，在该 Run 的逻辑实时流中不重复。生产者只提交观测，不自行分配计数器。多个订阅者观察同一个逻辑序号空间，不为每个订阅者或生产者另建序号空间。

P8.1 验证序号值边界并冻结其归属，但刻意不实现 Run 级 Sequencer 或 Publisher；这由 P8.3 完成。这不是分布式、全局持久顺序。P8.1 不承诺断线重放、跨节点延续序号、Redis 历史或缺口修复。

### Model Delta 语义

`MODEL_DELTA` 是提供方输出的观测，不是 Runtime 执行事实。它不能替代 `MODEL_COMPLETED` / `MODEL_FAILED`、触发 Runtime 状态转换、改变 `AgentRunResult`，或被持久化解释为完整模型响应。

未来 P8.2 流式 Adapter 遵循：

```text
提供方流
  -> 发出 MODEL_DELTA 观测
  -> 累积提供方输出
  -> 构造一个完整 ModelTurn
  -> 返回既有 AgentRunner
```

`AgentRunner` 仍消费 `ModelTurn`，不能变成逐 Token 驱动的状态机。

### ToolCall 累积规则

提供方流可能把 ToolCall ID、名称或参数拆成多个片段。部分 ToolCall 只是提供方协议的中间状态，绝不能进入 `ToolExecutor` 或触发外部副作用。

未来 Adapter 必须累积并验证完整 ToolCall，构造完整 `ToolCall` / `ModelTurn`，再交给 `AgentRunner`。既有 P3 治理链仍强制执行：

```text
完整 ModelTurn
  -> ToolRegistry 查找
  -> 反序列化
  -> Jakarta Validation
  -> Tool Policy
  -> 取消检查点
  -> TOOL_STARTED
  -> AgentTool.execute
```

流式不能绕过 Tool Governance。

### `ExecutionControl` 语义

`ExecutionControl` 是与提供方及基础设施无关的取消意图契约。它扩展既有 `CancellationContext`，因此可以通过 Runtime Context 传递，而无需修改已冻结的执行组件。

状态轴刻意精简：

```text
RUNNING -> CANCEL_REQUESTED
```

`requestCancel()` 是原子、单调、幂等操作：首次请求返回 `ACCEPTED`；后续返回 `ALREADY_REQUESTED`；同一次执行中不能从 `CANCEL_REQUESTED` 回到 `RUNNING`。

`CancelRequestResult` 还为未来活跃执行服务保留 `ALREADY_TERMINAL` 和 `NOT_ACTIVE`。本地 `ExecutionControl` 不判断 Run 是否持久化、是否终态或是否注册在其他节点；这些结果由未来外层控制服务负责。

### 协作式取消

取消是协作式的。调用 `requestCancel()` 不等于：

```text
Thread.interrupt()
Future.cancel(true)
终止提供方 HTTP 请求
终止 Tool 调用
修改 AgentRunEntity
删除或作废 Redis 租约
```

已经开始的 Model 或 Tool 操作保留真实的 Core 终态事实：

```text
MODEL_STARTED -> MODEL_COMPLETED 或 MODEL_FAILED
TOOL_STARTED  -> TOOL_SUCCEEDED 或 TOOL_FAILED
```

取消只能在显式安全检查点阻止未来工作。P8.1 定义规则，但不把检查点接入 `AgentRunner` 或 `ToolExecutor`。

### 安全检查点

冻结的检查点名称：

```text
BEFORE_MODEL
AFTER_MODEL
BEFORE_TOOL_EXECUTION
AFTER_TOOL_EXECUTION
```

未来的 `BEFORE_TOOL_EXECUTION` 检查位于工具查找、反序列化、验证、Policy 评估和 `ALLOW` 之后，但在 `TOOL_STARTED` 和真实副作用之前：

```text
resolve -> deserialize -> validate -> policy(ALLOW)
  -> BEFORE_TOOL_EXECUTION
  -> TOOL_STARTED
  -> AgentTool.execute
```

所以取消既不会跳过，也不会重新定义 Tool Governance。Model 和 Tool 的前后检查只阻止尚未开始的工作。

### `CANCELLED` Runtime 结果

显式取消是未来合法的 Runtime 停止原因，与 P7 基础设施所有权失败不同。因此，在安全检查点观测到取消时，已冻结的 Core 结果是 `AgentStopReason.CANCELLED`。未来 P8.4 集成可以发出 `RUN_STOPPED(CANCELLED)`；P8.1 不发出该事件，也不修改 `AgentRunner`。不能仅因观测到取消意图，就把取消映射成 `MODEL_ERROR`、`TOOL_ERROR` 或 `INTERNAL_ERROR`。

### 延迟与重复取消

当前 Core 调用一旦已经确定结果——`RUN_COMPLETED`、`RUN_STOPPED(CANCELLED)`、其他 `RUN_STOPPED` 原因或 `RUN_WAITING_APPROVAL`——之后的控制观测都不能改写该 Core 事实。对于已完成的调用，未来控制边界返回 `ALREADY_TERMINAL`，而不是把 `COMPLETED` 改成 `CANCELLED`。

`RUN_WAITING_APPROVAL` 是当前调用或执行片段的稳定结果，不表示持久化 Run 永久终结、永远不能继续。未来审批、Resume 或 Recovery 可以开始后续执行片段，但不能追溯改写此前进入 `WAITING_APPROVAL` 的调用。P8.1 不实现该继续流程。

终态前重复请求取消无害，返回 `ALREADY_REQUESTED`。`NOT_ACTIVE` 表示控制边界无法识别被请求 Run 的活跃或终态执行；P8.1 不实现区分这些情况所需的 Registry。

### 客户端断线语义

传输生命周期与执行生命周期独立：

```text
SSE 断线 != 取消 Run
```

浏览器刷新、移动网络中断、客户端超时或显式取消订阅，都不能隐式调用 `ExecutionControl.requestCancel()`。取消必须有显式控制请求。

### 流式失败语义

订阅者失败、慢客户端、SSE 写入失败与订阅者断线只影响观测可用性。它们不能改变 `AgentRunResult`、`AgentStopReason`、Runtime 事件、Tool Policy、持久化投影或租约所有权。Stream Publisher 必须隔离订阅者/传输失败与执行生产者；P8.1 尚不实现该 Publisher。

### 背压边界

Runtime 不能被缓慢的实时流订阅者无限期阻塞。未来 P8.3/P8H 可以使用有界订阅者缓冲区、断开慢订阅者，并且只对语义上可丢弃的观测定义丢弃行为；不得静默丢弃权威 Runtime 事实，同时声称提供完整流。

P8.1 不引入 Reactor、Flux、缓冲策略或 SSE 传输实现。

### 分布式控制边界

分布式取消属于 P8.5。P8.1 不创建 Redis 取消标记、Pub/Sub、Streams、分布式活跃控制 Registry 或接管行为。

Redis Run Lease 与未来 Redis Control 彼此独立：

```text
Redis Run Lease       = 执行所有权
Future Control Marker = 取消意图
```

两者不得共用 Key、Value、Token 或生命周期。未来命名空间候选：

```text
mma:control:v1:run:{runId}:cancel
```

P8.1 不创建或访问该 Key。

### 失败优先级

**失败优先级按阶段决定，不是一个全局排名。Core 执行事实与调用方可见的基础设施结果是两个不同层次。**

Core 终态事实一旦建立，后续持久化、协调、流式或控制观测都不能发出或合成不同的 Core 终态事实。对于 `WAITING_APPROVAL`，这一不可改写性同样适用于当前调用或执行片段的既定结果，但并不表示持久化 Run 永久终结。

Core 事实不可改写，并不代表之后观测到的所有基础设施失败都只能作为诊断。外层执行生命周期完成前，调用方可见的基础设施失败优先级仍由已冻结的 P6/P7 执行外壳契约决定。P8 不建立新的全局排名，也不重定义这些调用方契约。

当前 Core 调用尚未确定结果之前：

- 已冻结的 P6/P7 调用方可见失败契约继续生效；
- 取消只是控制意图，不能掩盖已经发生的持久化或协调失败；
- 已开始的 Model 或 Tool 仍记录真实终态事实；
- 如果取消在新工作开始前的安全检查点被观察到，而且该点没有 P6/P7 边界失败优先，那么未来 P8.4 可以确定 `RUN_STOPPED(CANCELLED)`。

当前 Core 调用已经确定结果之后：

- 后续观测不能合成第二个或不同的 Core 结果；
- P6 持久化或收尾失败可按 ADR-005 继续对调用方可见，但不能改写 Core 事实；
- P7 Watchdog Stop、协调边界和执行外壳失败维持既有的调用方可见或 suppressed 行为；
- 流式传输失败与延迟控制观测属于观测/控制问题，不能替换既定 Core 事实。

P7 已归类为终态清理诊断的失败仍只能作为诊断。特别是 Core 成功执行且持久化收尾完成后，Redis 比较并释放失败仍是终态清理诊断，不能替换 Core 结果。P8 不扩大该类别。

P8 不重新分类 P7 的 Watchdog Stop、协调边界或执行外壳失败。`watchdog.stop()` 建立与进行中续租的线性化边界，保障先停止再释放；其既有 P7 失败行为与 Redis 释放失败不同，本 ADR 不将它声明为“只能诊断”。

### 三条独立状态轴

P8 不得制造统一的超级状态；各状态轴回答不同问题：

| 状态轴 | 类型 | 回答的问题 |
|---|---|---|
| 所有权 | `RunLeaseSession` | 本次执行是否仍拥有该 Run？ |
| 控制 | `ExecutionControl` | 是否已请求取消？ |
| Runtime 结果 | `AgentRunResult` / `AgentStopReason` | Core 为何终止？ |

所有权可能已经 `LOST`，而控制仍是 `RUNNING`；取消已请求时，工具仍可能成功完成；之后的控制请求不能改变已确定的 Runtime 终态结果。

### 架构依赖规则

允许的方向：

```text
外层实时流 / 控制 Adapter
  -> Stream 契约
  -> Runtime 事实 / 通用执行控制契约
```

Runtime 可以依赖通用控制契约，但不得依赖 Stream 包、Controller、Spring Web/WebFlux、Reactor、SSE Adapter、Redis、Spring Data Redis、持久化实现、Pub/Sub 或节点身份。Stream 契约本身保持传输与基础设施中立。ArchUnit 强制验证这些边界。

## 暂缓事项

P8.1 明确不实现：

- 真实 Ollama/OpenAI 模型流式调用或 Spring AI Adapter 改动；
- 逐 Token 驱动的 Runtime 执行、ToolCall 片段组装器；
- StreamHub、SSE 端点、Reactor/Flux 集成或背压策略；
- Runtime 检查点接线，或线程、Future、HTTP、Model、Tool 中断；
- Redis 控制标记、Pub/Sub、Streams 或分布式取消；
- 暂停、恢复、重试、Recover、Replay 或 Takeover；
- Checkpoint 持久化、幂等、外部隔离或副作用 `UNKNOWN` 语义。

这些属于 P8.2～P8.5、P8H 或 P10。

## 后果

流式是执行的实时投影，不是执行事实权威。Runtime 事件保留自己的语义序号；实时流使用独立的共享序号，将 Runtime 事实、模型增量和控制观测交错排序。

取消是显式、幂等、单调且协作式的。已开始的操作保留真实的 Core 终态事实。传输失败、慢消费者、断线和延迟取消都不能改写执行事实。持久化历史、分布式所有权、实时观测、执行控制与 Runtime 结果始终是不同的语义权威。

## 关键契约原文对照

本节保留已由 `P8ContractDocumentationTest` 锁定的英文原句；对应中文语义已在上文阐明。翻译不改变这些冻结的约束。

One live execution stream for one runId has exactly one streamSequence authority.
Individual RuntimeEvent, ModelDelta, and ControlEvent producers do not own independent sequence spaces.

Failure precedence is phase-aware, not a global ranking.
Core execution truth and caller-visible infrastructure outcome are distinct layers.

Caller-visible infrastructure failure precedence remains governed by the
frozen P6/P7 execution-shell contracts until the outer execution lifecycle has completed.

Once a Core terminal fact has been established, no later persistence, coordination, streaming, or
control observation may emit or synthesize a different Core terminal fact.

Failures already classified by P7 as terminal cleanup diagnostics remain diagnostic-only.

P8 does not reclassify P7 watchdog-stop, coordination-boundary, or execution-shell failures.

Its existing P7 failure behavior therefore remains distinct from a
Redis release failure and is not declared diagnostic-only by this ADR.

`RUN_WAITING_APPROVAL` is a stable outcome of the current invocation or execution segment. It is
not a declaration that the durable Run is permanently terminal or can never continue.
