# ADR-005：执行持久化集成

- 状态：已接受
- 日期：2026-09-14

## 背景与范围

P6 将已冻结的 Runtime Kernel 接入现有的 `agent_runs`、`agent_steps` 和 `tool_executions` 表，加入持久化准入、Core 事件的同步投影与终态结果收尾。不实现恢复、重试、Redis 协调、幂等强制、流式，也不迁移 `ChatService`。

Runtime 仍是执行语义与 Core 事件事实的权威来源。数据库只对成功写入的历史负责。P6 不宣称 Core 状态、数据库事务、模型提供方活动和工具外部副作用之间具有原子性。

## 决策

### 集成边界

`PersistentAgentExecutionCoordinator` 包裹既有 `AgentExecutionCoordinator`，处理持久化准入和收尾。`ExecutionPersistenceEventPublisher` 通过 `ExecutionHistoryStore` 同步投影已发出的事实。`ExecutionPersistenceBoundaryMiddleware` 在模型和工具调用的前后边界观测已经记录的基础设施失败。Runtime 类不导入集成包、Repository、JPA、Hibernate、Spring AI 基础设施或 Redis。

已冻结的 Event Emitter 会捕获 Publisher 异常。因此，持久化 Publisher 将第一次失败记录到 Run 级 Registry，而不试图穿透 Emitter 抛出。Middleware 在第一个语义上安全的边界阻止后续执行，外层 Coordinator 则向调用方暴露原始 `ExecutionPersistenceException`。

拒绝的替代方案包括：跨整个 Run 的事务、Runtime 直接调用 Repository、异步尽力而为订阅者，以及只在 Run 结束后持久化。它们分别跨越远程调用/副作用边界、倒置依赖、掩盖持久历史丢失，或无法阻止后续工作。

### Core 事件到持久化的映射

| Core 事件 | Run 投影 | Step / Execution 投影 | 代表实际调用吗 |
|---|---|---|---|
| `RUN_STARTED` | `RUNNING`，开始时间取自 `occurredAt` | 无 | Core Run 已开始 |
| `MODEL_STARTED` | 轮次，`MODEL_RUNNING` | 新建 `RUNNING` 的 MODEL Step | 到达模型分发边界 |
| `MODEL_COMPLETED` | `FINALIZING` 或 `AWAITING_TOOL` | MODEL Step 为 `SUCCEEDED` | 模型完成 |
| `MODEL_FAILED` | `FINALIZING` | MODEL Step 为 `FAILED`，带 Core 停止原因 | 真实模型失败 |
| `TOOL_REQUESTED` | 轮次，`AWAITING_TOOL` | 新建 `PLANNED` 的 TOOL Step 与 ToolExecution | 否 |
| `TOOL_VALIDATED` | 轮次，`AWAITING_TOOL` | 不在 `PLANNED` 之外作状态宣称 | 否 |
| `TOOL_VALIDATION_FAILED` | `FINALIZING` | Step 为 `SKIPPED`，Execution 为 `BLOCKED` | 否 |
| `TOOL_POLICY_EVALUATED(ALLOW)` | 轮次，`AWAITING_TOOL` | 开始前仍为 `PLANNED` | 否 |
| `TOOL_POLICY_EVALUATED(DENY)` | 轮次，`AWAITING_TOOL` | Step 为 `SKIPPED`，Execution 为 `BLOCKED` | 否 |
| `TOOL_POLICY_EVALUATED(REQUIRE_APPROVAL)` | 轮次，`AWAITING_TOOL` | 仍为 `PLANNED` | 否 |
| `TOOL_STARTED` | 轮次，`TOOL_RUNNING` | Step 为 `RUNNING`，Execution 为 `STARTED` | 是 |
| `TOOL_SUCCEEDED` | 轮次，`AWAITING_TOOL` | Step / Execution 为 `SUCCEEDED` | 已成功完成 |
| `TOOL_FAILED` | `FINALIZING` | 只有真实执行失败才记 `FAILED`；未知/非法调用记 `BLOCKED` | 仅在 Core 认定执行失败时 |
| `RUN_COMPLETED` | `COMPLETED` 和终态时间 | 无 | Core 终态事实 |
| `RUN_STOPPED` | `FAILED` / `CANCELLED`、停止原因和终态时间 | 无 | Core 终态事实 |
| `RUN_WAITING_APPROVAL` | `WAITING_APPROVAL`，不设置完成时间 | 已规划工具仍未开始 | Core 终态事实 |

`finalizeRun` 校验持久化的终态停止原因与 `AgentRunResult.stopReason` 相同。只有已完成的 Run 才保存 `finalContent`。

### 身份、顺序与关联

- 准入时保留执行请求中的 `runId`、`requestId`、`sessionId` 和 `userId`。
- MODEL Step 的身份由 `(runId, iteration)` 确定性生成 name UUID。
- TOOL Step 和 Execution 的身份由 `(runId, toolCallId)` 确定性生成 name UUID。
- 提供方/Runtime 的 `toolCallId` 原样存储，绝不重新生成。
- `stepIndex` 从该 Run 已持久化的 Step 数量分配，并受 `(run_id, step_index)` 唯一约束保护。它用于还原 MODEL/TOOL 顺序，不依赖数据库主键排序。
- `iteration` 始终来自已冻结的 Core 事件。

P6 假设一个 Run 只有一个活跃写入者。并发协调有意留待后续；竞争会由数据库唯一约束或乐观锁暴露，而不是静默覆盖。

### 事务与乐观锁

准入、每次事件投影和收尾分别使用短事务 `REQUIRES_NEW`。没有事务跨越模型推理、HTTP 调用或工具副作用。AgentRun 与 ToolExecution 既有的 `@Version` 字段仍是权威约束；乐观锁冲突属于持久化基础设施失败，绝不归类为模型或工具失败。

### 时间语义

执行时间戳 `startedAt` / `completedAt` 来自 `AgentEvent.occurredAt`，表示 Core 事实何时发生。SQL Schema 以 `TIMESTAMP(6)` 精度存储。Entity 的 `createdAt` / `updatedAt` 来自 JPA Callback，只是持久化元数据，不可当作事件发生时间。

### 失败语义

Core 启动前必须完成持久化准入。若准入失败，模型和工具调用次数均为零，不发出 `RUN_STARTED`，调用方收到 `ExecutionPersistenceException`。

Core 事实产生后的持久化失败不能改写该事实，尤其不得出现 `MODEL_COMPLETED -> MODEL_FAILED`、`TOOL_SUCCEEDED -> TOOL_FAILED` 或 `RUN_COMPLETED -> RUN_STOPPED`。若模型或工具的已冻结 STARTED 事实已经发出，允许该操作到达真实的 Core 终态事实；随后在操作后置 Middleware 边界阻止下一步。若 `RUN_COMPLETED` 已出现，它仍是最后一个 Core 事件。

外层 Coordinator 始终让持久化失败对调用方可见。它不是 `MODEL_ERROR` 或 `TOOL_ERROR`。不向 `AgentStopReason` 增加 `PERSISTENCE_ERROR`，因为该枚举描述 Core 结果，而数据库失败可能发生在 Core 已完成之后。现有已冻结的 Middleware 转换逻辑可能在 Middleware 边界阻止后续执行时产生 Core `RUN_STOPPED(INTERNAL_ERROR)`；调用方仍收到独立的持久化异常。

`MODEL_STARTED` 紧接真实 `model.generate` 调用前发出；该回调中的持久化失败不能阻止已经开始的操作，需待其终态事件后检查。`TOOL_STARTED` 紧接 `AgentTool.execute` 前发出，同理。因此 P6 不引入虚假的 STARTED 记录，也不重新定义已冻结的生命周期语义。

### 隐私与 Schema

P6 持久化执行元数据及必需的已完成 `finalContent`，不保存完整用户对话、Prompt、模型请求/响应正文、思维链、原始工具参数或原始工具结果。现有可选的 Hash / Summary 字段不会填入敏感原始数据。只投影结构化 Core 错误码；当前事件契约没有独立清洗过的消息来源，因此 `errorMessage` 保持空值。

V1 和 V2 已包含必需的列、枚举表示、唯一约束、外键、时间戳和版本字段，保持不可变，无需 V3 Migration。已有的空库和 V1→V2 升级测试继续构成迁移契约。

### 暂缓事项

重试依赖副作用与幂等语义，故暂缓。P6 无法推断或修复外部副作用的部分可见结果，故恢复与对账暂缓。持久历史不需要第二个状态权威，故 Redis 协调暂缓。幂等强制留待定义准入/重放行为的阶段；P6 只保留已有请求和工具关联字段。

### P6H 加固不变量

`ExecutionPersistenceComposition` 是生产组合根。其 Event Publisher、Boundary Middleware 和 Persistent Coordinator 共享对象身份相同的 `ExecutionPersistenceFailureRegistry`；调用方必须通过该组合根取得三者，不得自行组装互不关联的 Registry。

持久化投影器维护与完整 sealed `AgentEvent` 层级一致的显式白名单。没有显式投影的新 Event 类会以 `IllegalArgumentException` 封闭式失败，不能仅通过刷新 Run 行便视为已处理。

如果同一次执行既有真实 Core 失败，也有持久化失败，两种事实保持区分：Core 仍发出 `MODEL_FAILED` / `RUN_STOPPED(MODEL_ERROR)` 或 `TOOL_FAILED` / `RUN_STOPPED(TOOL_ERROR)`；外层持久化边界则使此前记录的 `ExecutionPersistenceException` 成为调用方的优先异常。调用方异常优先级绝不改写 Core 事件。

### 约束与测试契约

ArchUnit 禁止 `agent.runtime..` 依赖持久化 / Repository / Application 层、JPA / Hibernate、Spring Data / Spring AI 或 Redis。确定性测试必须覆盖直接成功、工具往返、模型/工具失败、Policy 拒绝、等待审批、最大轮次、多工具顺序、时间戳以及所有指定的持久化失败边界。P6 之前的完整确定性测试套件必须保持通过。

## 后果

成功执行现在有可查询的 Run、Model Step、Tool Step 和 ToolExecution 持久历史，同时 Runtime 保持框架与持久化中立。刻意保留的限制是：数据库失败后，Core 事实可能存在却没有对应持久化行；P6 会暴露该情况并阻止后续执行，但不重试、恢复或宣称跨系统原子性。
