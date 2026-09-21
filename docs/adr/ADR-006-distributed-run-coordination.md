# ADR-006：分布式 Run 协调

- 状态：已接受，并已通过 P7H 完成语义加固
- 日期：2026-09-16

## 背景与范围

P6 让执行历史可持久化，但仅靠数据库持久化不能阻止两个应用实例并发尝试执行同一 `runId`。P7 引入分布式协调，但不把 Redis 变成第二套 Runtime 状态机。

P7.1 仅冻结协调语义、领域端口、失败分类、租约 Session 状态模型、配置契约与架构约束；它不连接 Redis，也不运行受协调的 Run。

## 决策

### 权威边界

三个状态权威刻意分离：

- Runtime Core 负责执行事实：模型、工具、轮次和终态。
- MySQL 负责成功持久化的执行历史及 Run 身份。
- Redis 只保存活跃执行所有权的临时协调状态。

Redis 不得保存或决定轮次、模型、工具、Run 终态、对话历史、Checkpoint、恢复状态或持久历史。Runtime Core 不得依赖 Redis、Spring Data Redis、协调 Adapter 实现或持久化实现。

### 协调范围与所有权

协调以 `runId` 为范围，而非 `sessionId`。P7 保证一个 Run ID 最多只有一个活跃租约持有者，但不串行化同一会话中的所有 Run。

租约竞争应直接拒绝，而非等待。第二个调用方遇到活跃的 `runId` 时，必须收到 `RunAlreadyActiveException`，不能等待首个持有者结束后再次执行同一 Run。

完整 P7 的预期组合顺序：

```text
AgentExecutionRequest
  -> CoordinatedAgentExecutionCoordinator
  -> 获取 Run 租约
  -> PersistentAgentExecutionCoordinator
  -> 持久化准入
  -> AgentExecutionCoordinator
  -> Runtime
  -> 持久化收尾
  -> 停止续租
  -> 比较令牌后删除租约
```

P7.1 记录该组合；P7.3 实现获取/委托/终态释放的外壳；P7.4 增加自动续租和 Runtime 安全边界约束。生产入口的启用仍暂缓。

### Redis 与数据库约束互补

Redis 保护并发活跃所有权；数据库唯一约束继续保护持久化 Run 身份。成功获取 Redis 租约不代表可以把 Run 当作全新 Run 执行：早先执行可能已有持久行，其临时租约却已经过期或释放。因此，获取租约后仍必须进行持久化准入。

`leaseToken` 是不可猜测的所有权凭证，用于“比较并续租”和“比较并删除”；它不是业务 ID、不是 `runId`，也不能用 Redis Key 代替。未来默认 Key Schema：

```text
mma:coord:v1:run:{runId}:lease
```

Key 的构造属于未来 Redis Adapter，而非领域契约。

### 租约生命周期与执行权

已冻结的 Session 状态为 `ACTIVE`、`LOST`、`CLOSING`、`CLOSED`，合法转换：

```text
ACTIVE -> LOST
ACTIVE -> CLOSING
LOST -> CLOSING
CLOSING -> CLOSED
```

禁止 `LOST -> ACTIVE`、`CLOSED -> ACTIVE` 和 `CLOSED -> LOST`。所有权丢失具有单调性：一旦无法证明所有权，后续基础设施恢复或看似成功的续租都不能恢复本次执行的权限。新执行必须获取新租约并使用新 Session。

`EXPLICIT_LEASE_LOSS` 表示协调层已明确证明当前 Token 不再是持有者，例如 Token 不匹配或 Key 缺失。`COORDINATION_UNAVAILABLE` 表示基础设施不可用或结果未知，因而无法证明所有权。两者诊断上不同，但都会使 Session 从 `ACTIVE` 变为 `LOST`，并禁止后续 Core 工作。

P7 仅在 Runtime 操作边界提供协作式执行隔离。若模型或工具已经发出 `MODEL_STARTED` 或 `TOOL_STARTED`，必须允许其到达真实的 Core 终态事实，然后才能阻止未来工作。协调失败可以阻止后续 Core 工作，但不能改写已经形成的 Core 事实。

这不是外部资源隔离。工具调用的远程系统若不参与 Fencing Token 或幂等协议，就无法阻止过期持有者在该系统产生副作用。

### 失败模型与 Core 结果

协调失败属于 Harness / 基础设施结果，而不是 Runtime Core 结果。因此 P7 不向 `AgentStopReason` 增加 `COORDINATION_ERROR`、`REDIS_ERROR` 或 `LEASE_LOST`。

异常层级：

```text
ExecutionCoordinationException
  |- RunAlreadyActiveException
  |- RunLeaseLostException
  `- CoordinationUnavailableException
```

`RunLeaseStore` 的结果保持 Redis 中立，且不将不同结果压缩成布尔值：

- 获取：成功、已被占用或协调不可用；
- 续租：成功、明确丢失所有权或协调不可用；
- 释放：成功、不再是持有者或协调不可用。

### P6 与 P7 失败优先级

如果同一次已经开始的操作同时发生持久化和协调失败，调用方的首要异常是 `ExecutionPersistenceException`，以保留 P6 的调用方可见契约。协调失败必须保留为诊断信息，例如 suppressed exception 或结构化观测；它不能替换持久化失败或改写 Core 事实。

该优先级只适用于执行已经进入 P6 / Runtime 路径且两种基础设施失败都存在的情况。如果租约获取在 P6 持久化准入前失败，只返回相应的 `ExecutionCoordinationException`，因为尚不存在持久化失败。

P7.1 不通过修改 P6 伪造双重失败路径，只为后续组合冻结规则。

### 配置契约

Redis Adapter 由 `enabled`、`keyPrefix`、`leaseTtl`、`renewInterval` 和 `watchdogThreads` 配置。默认关闭，前缀为 `mma:coord:v1:run`，TTL 为 60 秒，续租间隔为 20 秒，共享 Watchdog Scheduler 默认有 4 个 Daemon 线程。两个时长必须为正，前缀非空，线程容量为正，且一个 TTL 至少容得下三个续租间隔。

声明这些配置本身不会启用协调。显式启用后，Spring 可以组合 Redis Store、一个共享续租 Scheduler、Watchdog Factory 和协调边界 Middleware；但应用执行入口仍未切换到协调流程。

### P7.2 Redis 租约原语

P7.2 使用 Spring Data Redis 实现 `RunLeaseStore` 端口，但不接入执行生命周期。获取使用一次原子 Redis `SET key token NX PX ttl`。续租使用原子 Lua 脚本比较 Token 后执行 `PEXPIRE`；释放使用原子 Lua 脚本比较 Token 后执行 `DEL`。Redis 客户端失败映射为既有的“协调不可用”结果，不通过领域端口泄漏 Redis 或 Lettuce 异常。Adapter 不改变 `RunLeaseSession`；生命周期状态由后续协调接线负责。

只有 `multimodal-agent.coordination.redis.enabled=true` 时，Adapter 才能注册为 Spring Bean。仅定义原语不会获取租约，也不会接入 Agent 执行。

### P7.3 受协调的执行生命周期

P7.3 将一次执行放入所有权外壳：

```text
获取 Run 租约
  -> 创建 ACTIVE RunLeaseSession
  -> 执行 PersistentAgentExecutionCoordinator
  -> 终态清理时按 Token 释放租约
  -> 关闭 RunLeaseSession
```

租约竞争或不可用在 P6 持久化准入之前按封闭原则失败。获取成功后，即使 P6 准入、Runtime 或持久化收尾失败，清理逻辑仍会尝试释放。若委托执行和清理同时失败，委托异常仍是首要异常，协调异常作为 suppressed exception。尤其不能破坏上文冻结的 `ExecutionPersistenceException` 优先级。

成功的委托执行之后，释放失败不能改写已完成的 Core 结果。释放不可用记为 `COORDINATION_UNAVAILABLE` 诊断；`NO_LONGER_OWNER` 先使 Session 进入 `LOST`，并记录明确的租约丢失诊断。两种情况随后都关闭本地 Session，不盲目删除、重试或新增 `AgentStopReason`。

生命周期 Coordinator 有意不注册为生产应用入口。

### P7.4 Watchdog 续租与安全边界

P7.4 在获取租约后、P6 准入前，为本次执行启动一个 `RunLeaseWatchdog`。所有 Watchdog 共用注入的 Scheduler；单次执行不创建独立线程。清理总是先停止 Watchdog，再按 Token 释放；`stop()` 与续租串行化，避免旧任务在释放后再次续租。

每次 Tick 通过 `RunLeaseStore` 原子续租。`RENEWED` 保持 `ACTIVE`；`EXPLICIT_LEASE_LOSS`、`COORDINATION_UNAVAILABLE` 和意外续租异常都使 Session 永久进入 `LOST`，并保留第一次失败。基础设施恢复不能让该执行重新取得权限。

协调 Middleware 位于持久化 Middleware 外层：

```text
协调前置检查
  -> 持久化前置检查
    -> Core 操作
  -> 持久化后置检查
-> 协调后置检查
```

协调顺序值为 100，持久化顺序值为 200。当前 `RunLeaseSession` 通过通用 Harness Context Contributor 和强类型 `RuntimeAttributes` 进入唯一的 `AgentRuntimeContext`；不存在 `ThreadLocal`、静态执行 Registry、第二套 Context 或每个边界上的 Redis 查询。

若所有权已丢失，新 Model 或 Tool 操作不得开始。若在已经开始的操作过程中丢失所有权，该操作仍先发出真实的 `MODEL_COMPLETED`、`MODEL_FAILED`、`TOOL_SUCCEEDED` 或 `TOOL_FAILED`，然后才由后置检查阻止未来工作。Core 使用已冻结的 Middleware 结果 `RUN_STOPPED(INTERNAL_ERROR)`。外层 Coordinator 随后向调用方暴露 Session 对应的 `RunLeaseLostException` 或 `CoordinationUnavailableException`。若持久化也失败，`ExecutionPersistenceException` 仍是首要异常，协调失败保留为一个 suppressed 诊断。

P7.4 是 Runtime 边界上的协作式隔离，不隔离工具调用的外部系统，不中断正在进行的远程请求，也不恢复丢失的 Run 或将执行移交新持有者。

### P7H 并发与生命周期不变量

P7H 冻结以下约束：

- `stop()` 与续租在同一个 Watchdog Monitor 上线性化。`stop()` 等待正在进行的续租；返回后，`RunLeaseStore` 内没有仍在执行的续租，后续调度回调也不能进行有效续租。清理顺序必须是 `stop -> release`。
- 旧 Token 的延迟续租在释放或重新取得所有权后仍安全，因为 Redis 续租是原子的“比较 Token 后续期”。它既不能延长，也不能删除新持有者的租约。
- 对一次执行而言，`LOST` 不可逆。Redis 后来恢复正常也不能恢复权限；P7 不自动重新获取已丢失的租约。
- `CLOSED` 是终态。清理开始后，异步续租和 Scheduler 回调采用无操作状态转换；它们不能产生 `CLOSED -> LOST`、恢复权限或从回调泄漏生命周期异常。
- Token 明确丢失与协调不可用保持不同诊断，尽管两者都封闭式失败并阻止后续 Core 工作。
- Scheduler 可用性也是协调可用性的一部分。关闭生产 Scheduler 会通知所有活跃 Watchdog，使其 Session 变为 `LOST(COORDINATION_UNAVAILABLE)`；首次调度被拒绝同样按封闭原则处理。
- 共享 Scheduler 是可用性边界，因为续租使用阻塞 Redis 调用。仅两个线程时，两次慢调用便可饿死其他所有 Run；因此保守默认值为 4，且 `watchdogThreads` 可配置。这是容量加固，不是动态扩容。
- “先停止再释放”的安全性要求等待正在进行的 Redis 续租。只有 Redis 操作有完成时限，这种等待才安全。生产配置将 Lettuce 命令超时和连接超时均设为 2 秒。若操作先连接、再发命令，顺序预算约为 4 秒，加上本地调度开销，而不是无限等待。
- Context Contributor 按请求顺序、每次执行恰好一次地补充唯一的 `AgentRuntimeContext`。它不能替换 Context 或执行 Core 工作。Contributor 在 Core 启动前失败时，没有 Core 事件；外层协调外壳仍停止续租、释放租约并关闭 Session。
- Middleware 顺序值 100（协调）和 200（持久化）是语义契约，不是偶然数字。实际调用顺序为协调前检、持久化前检、Core、持久化后检、协调后检。顺序值相同的 Middleware 通过扩展内核稳定排序保留注册顺序。
- 持久化/协调失败的优先级不受时序影响。同一次已经开始的操作同时存在两者时，`ExecutionPersistenceException` 仍为首要异常。协调观测作为 suppressed 诊断。同一次明确所有权丢失的重复观测合并；来自 Session、Watchdog Stop 和 Release 的不同“不可用”失败分别保留。
- 完成的 Model 或 Tool 操作即使在随后的安全边界遇到持久化或协调失败，也保留真实的 Core 终态事件。后续 Model 或 Tool 工作按封闭原则停止；既有事实永不改写。
- Scheduler 关闭、慢续租与共享容量仅影响可用性，不使 Redis 成为执行事实权威，也不引入恢复、重放、接管或外部副作用隔离。

### 暂缓事项

P7 有意不实现：

- 生产环境启用协调执行流程；
- 恢复、重放、重试、取消、暂停/恢复或流式；
- Session 级串行化、排队或新消息打断；
- Redis Pub/Sub 或 Streams；
- Fencing Token / Epoch 或外部资源隔离；
- 工具幂等、分布式事务或外部副作用恰好一次。

这些属于后续 P7 / P8 / P10 阶段。

## 后果

协调契约保持确定性、Redis 中立，Adapter 将明确的结果映射为原子 Redis 命令。共享 Watchdog Scheduler 维护长时执行的所有权，执行级 Session 保持精简且单调的生命周期。Runtime 安全边界会阻止未来的协作式工作，但不会改写 Core 历史。生产启用、恢复、取消和外部资源隔离仍不属于 P7。
