# ADR-001：Agent Runtime 边界与持久化事实源

- 状态：Accepted
- 日期：2026-09-09

## 背景

项目已经具备确定性的 Agent Loop、强类型 Tool 参数校验和 Tool Policy，但缺少可审计、可查询并可供未来恢复使用的运行状态模型。

## 决策

1. Spring AI 负责模型 Provider、标准 Tool Calling 和 Streaming 等模型能力的适配。自研 Runtime 负责 Agent Loop、Tool Governance、Context、Policy、StopReason，以及未来的 Checkpoint 与 Recovery。
2. Runtime DTO 与持久化模型分离。`AgentRunSpec`、`AgentRunResult` 描述如何运行及运行结果；`AgentRunEntity`、`AgentStepEntity`、`ToolExecutionEntity` 记录实际发生的状态，不直接作为 Controller API DTO。
3. Database 是最终事实源。未来 Redis 只承担热数据、实时状态和协调；Run、Step、ToolExecution 与 Checkpoint 的权威状态保存在数据库。
4. Tool 执行边界保持为 `ToolRegistry -> Deserialize -> Validation -> Policy -> Execute`，持久化基础不改变现有执行语义，也不把 JPA Repository 注入 `AgentRunner`。
5. 外部副作用未来通过 Checkpoint、Idempotency 和 Transactional Outbox 治理。本阶段只预留状态与幂等字段，不实现恢复、重放或 Outbox。
6. Run、Step、ToolExecution 之间使用业务 ID 列和数据库外键表达关系，Entity 不建立双向对象图。`request_id` 作为全局请求幂等标识使用唯一约束；`tool_call_id` 只要求在同一个 Run 内唯一。

## 数据库迁移兼容策略

Flyway 从 V1 开始管理新增的 Agent Runtime 表。原有业务表目前仍由 Hibernate `ddl-auto=update` 管理；为兼容已经存在且没有 Flyway 历史表的数据库，配置 `baseline-on-migrate=true` 和 baseline version `0`，随后执行 V1。待旧业务表逐步纳入后续 Migration 后，再关闭 `ddl-auto=update`。

## 后果

- Runtime 执行内核继续保持框架无关和可做确定性单元测试。
- 数据库可以独立查询一次 Run、每个 Step 和每次 Tool 调用。
- 当前不具备 Checkpoint Resume、自动恢复、Redis 协调或 Outbox 投递能力。
