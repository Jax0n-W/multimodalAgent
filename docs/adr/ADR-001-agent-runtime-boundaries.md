# ADR-001：Agent Runtime 边界与持久化事实源

- 状态：Accepted
- 日期：2026-09-09

## Context

项目已经具备确定性的 Agent Loop、强类型 Tool 参数校验和 Tool Policy，需要在接入 AgentEvent、Decision Trace、Checkpoint 与 Recovery 前冻结运行边界、持久化事实和安全约束。项目当前同时包含 WebFlux、JPA、POI 等阻塞组件，并仍有 legacy 表依赖 Hibernate 自动更新。

## Decision

### 1. Runtime 与 Spring AI 边界

Spring AI 负责模型 Provider、模型协议、标准 Tool Calling 和 Streaming 适配。自研 Java Runtime 负责 Agent Loop、Tool Governance、Context、Policy、状态、StopReason，以及未来的 Checkpoint 与 Recovery。

### 2. Runtime DTO 与 Persistence Entity 分离

`AgentRunSpec`、`AgentRunResult` 描述如何运行及运行结果；`AgentRunEntity`、`AgentStepEntity`、`ToolExecutionEntity` 记录已经发生的持久化事实。Entity 不作为 Controller API DTO，Runtime 内核也不直接依赖 JPA Repository。

### 3. Database 与 Redis 职责

Database 保存 durable facts、幂等 ownership 和恢复依据，是最终事实源。未来 Redis 只承担 hot state、实时协调和事件 fast path；Redis 丢失不能破坏正确性。

### 4. 请求幂等归属

`requestId` 是平台级 globally unique identifier，数据库 `UNIQUE(request_id)` 是最终执行权判定。未来 Redis 只做快速查询：Redis miss 后仍以 DB INSERT 成功或唯一约束冲突决定 ownership，再回填 Redis。

### 5. Session Lock 生命周期

未来 Session Lock 只保护 Active Execution Segment。进入 `WAITING_APPROVAL` 前必须保存状态/Checkpoint 并释放 Lock；恢复时重新获取 Lock，通过 CAS 或状态校验确认仍可恢复后继续。本阶段不实现 Lock、Checkpoint 或 Resume。

### 6. Tool 重试分类

- `READ_ONLY`：允许有限自动重试。
- `IDEMPOTENT_WRITE`：仅允许携带同一个 idempotencyKey 的有限重试。
- `NON_IDEMPOTENT_WRITE`：禁止基础设施盲目自动重试。
- STARTED 后发生模糊超时：记录为 `UNKNOWN`，不能直接当作 FAILED 或安全重放。

本阶段不实现 Retry Framework。

### 7. Tool 执行状态

正常状态流为 `PLANNED -> STARTED -> SUCCEEDED / FAILED / UNKNOWN`。Policy 或 Safety 在真实副作用前拒绝时为 `BLOCKED`；安全可取消阶段终止时为 `CANCELLED`。`UNKNOWN` 表示副作用可能已经发生但结果无法确认，与 `FAILED` 不等价。

`ToolExecution.idempotencyKey` 在 V1 契约中是平台生成的 globally unique identifier，推荐 UUID 或带全局命名空间的值，不是 Tool 自己生成的局部计数器。因此保留 `UNIQUE(idempotency_key)`。

每条 ToolExecution 必须满足应用层不变量：其 `stepId` 对应 AgentStep 的 `runId` 必须等于 ToolExecution 自身的 `runId`。当前数据库使用两个独立 FK，本阶段不增加复杂组合 FK，未来写入 Adapter 必须校验该不变量。

### 8. WebFlux 与 Blocking 边界

项目保留 WebFlux。JPA、POI 和其他 blocking IO 接入 reactive chain 时，必须隔离到 `boundedElastic` 或 dedicated blocking executor。当前 Persistence Foundation 尚未接入 Agent Runtime 的 reactive execution path。

### 9. Prompt Injection 安全口径

项目只能宣称提供 Prompt Injection Mitigation，不能宣称 fully solved 或 100% safe。真实执行边界由 Schema Validation、PolicyEngine、未来 RBAC、Approval 和业务规则共同构成。

### 10. Flyway/Hibernate 过渡状态

Runtime 表的创建与版本化以 Flyway Migration 作为规范来源。由于 legacy schema 仍依赖 `ddl-auto=update`，同一 persistence unit 下 Hibernate 当前仍会检查 Runtime Entity 对应 Schema；这是 V1 的明确过渡状态，不宣称 Flyway 已是所有表的唯一 schema authority。

后续 Schema Migration Cleanup 阶段将把 legacy 表纳入 Flyway，并把 `ddl-auto` 改为 `validate`，届时 Flyway 成为唯一 Schema authority。该过渡状态不阻塞 Phase 4。

### 11. Run 与 Step 投影

AgentRun 使用 `userId` 表达归属，`currentIteration` 表达当前持久化轮次：CREATED 为 0，开始第 N 轮时为 N，WAITING_APPROVAL 保存当前轮，终态保留最后实际轮次。AgentStep 的 `stepIndex` 是 Run 内稳定全局顺序，`iteration` 仅表示模型轮次，不使用数据库自增 ID 作为业务顺序。

V2 直接把 `user_id` 加为 `NOT NULL`，其前提是当前 V1 尚无已持久化的 AgentRun 数据；如果其他部署环境已经存在 V1 Run 数据，必须先按业务归属完成 `user_id` 回填，再执行该约束迁移。

## Alternatives

- 完全委托 Spring AI 执行 Agent Loop：不能满足自定义治理、状态与恢复要求，拒绝。
- Redis 作为唯一状态源：无法提供需要的持久事实和数据库唯一约束，拒绝。
- Entity 直接作为 Runtime/API 模型：会把 JPA 生命周期泄漏到执行内核和接口，拒绝。
- 本阶段拆分 persistence unit 或全量迁移 legacy schema：改动范围过大，延后到 Schema Migration Cleanup。
- 为 ToolExecution 增加复杂组合 FK：可增强数据库不变量，但当前收益不足以抵消迁移复杂度，暂由未来写入 Adapter 校验。

## Rationale

这些选择让模型适配、执行治理和持久化事实保持独立，使 Runtime 可做确定性单元测试；数据库唯一约束、稳定 stepIndex 和 optimistic locking 为后续事件记录、审批恢复和并发状态更新提供最小可靠基础。

## Consequences

- 可以独立查询一次 Run、其稳定顺序的 Step 和每次 Tool 调用。
- AgentRun 与 ToolExecution 的 stale write 会由 JPA optimistic locking 拒绝。
- Runtime 内核继续保持无 JPA 依赖。
- 当前仍不具备 AgentEvent、Decision Trace、Checkpoint Resume、自动恢复、Redis 协调、Retry 或 Outbox。
- Flyway 与 Hibernate hybrid 模式仍是明确接受的 V1 技术债。

## Boundaries

本 ADR 冻结 V1 的责任边界和数据语义，不授权实现 Phase 4 事件、审批 API、Redis、Recovery、Outbox、Retry、MCP/RAG 重构或多 Agent。任何外部副作用能力必须继续经过 `ToolRegistry -> Deserialize -> Validation -> Policy -> Execute`。
