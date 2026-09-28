# ADR-014：执行配置快照

## 状态

P9.3 已实现。本 ADR 冻结一次 Agent Run 所使用的 resolved execution config 的身份、持久化时序和不可变语义，不引入 P9.4 Eval 或 P10 Recovery/Resume。

## 背景

只在 `application.yml` 中保存“当前配置”，无法回答某个历史 Run 当时实际使用了哪个模型、超时、Runtime 限制和预算。配置文件还可能被环境变量覆盖，Run 创建后也可能发生变化。因此审计依据必须是执行路径真正消费的 resolved object，而不是事后重新读取配置文件。

## 决策

每次生产执行先构造不可变的 `ResolvedExecutionConfig`，内容仅包括当前确实存在且影响执行的配置：

- Model：`ModelIdentity`、temperature、单次最大生成 token、`ModelTimeoutPolicy`；
- Runtime：`AgentRunSpec.maxIterations` 与稳定排序后的 `allowedTools`；
- Budget：`ExecutionBudget` 的调用、token、cost 限制与显式 `ModelPricing`。

模型 Adapter 与 Snapshot Resolver 共用同一个 `ResolvedModelConfig`。Runtime 与预算信息直接来自本次 `AgentRunSpec`。Snapshotter 不重新读取或拼装 `application.yml`，也不伪造尚未存在的 prompt、skill 或 retrieval policy version。

执行顺序固定为：

```text
resolve effective config
→ canonicalize and SHA-256
→ persist immutable snapshot
→ attach snapshotId to AgentExecutionRequest
→ P7 coordination
→ P6 durable admission
→ Agent Runtime
```

`SnapshottingAgentExecutionCoordinator` 是 P7/P6 外层的 execution guard。只有 Snapshot 已成功持久化并得到相同内容的确定性身份后，才允许调用下游。Snapshot persistence 失败时，不创建 `AgentRun`，不产生 Runtime event，不调用 Provider，也不产生 Tool side effect。

## 确定性身份

Canonical JSON 使用固定 schemaVersion、固定字段顺序、稳定的 ISO-8601 Duration 表示、规范化的 BigDecimal 字符串，以及去重并排序后的 `allowedTools`。身份计算为：

```text
configHash = SHA-256(canonicalJson)
snapshotId = exec-config-v{schemaVersion}-{configHash}
```

语义相同的 resolved config 必须得到相同 JSON、hash 和 snapshotId；任何影响执行的配置变化必须得到不同身份。同一 snapshotId 已存在且内容完全相同时复用；内容不同时 fail hard，禁止覆盖。

## 持久化与历史兼容

`agent_runtime_config_snapshots` 采用 insert-once、never-update 语义，保存 schema version、hash、canonical JSON 和创建时间。`agent_runs.runtime_config_snapshot_id` 通过外键永久关联 Snapshot。

V3 migration 将该外键列保持 nullable，以兼容 P9.3 之前已经存在的历史 Run；P9.3 之后的 production execution 由外层 Snapshot coordinator 保证在 P6 admission 前附加非空 snapshotId。后续应用配置变化或产生新 Snapshot，不得改写历史 Run 的关联或旧 Snapshot 内容。

## 密钥边界

Snapshot 只接受白名单式的 resolved domain object，不序列化完整 Spring properties。API key、Authorization、Redis/DB/mail password、credential 或其他 token secret 均不属于 Snapshot schema，不得进入 canonical JSON 或 hash 输入。

## 架构边界

Snapshot resolution 与 execution guard 位于 application/integration 边界；JPA store 位于 persistence integration。`AgentRunner`、`ModelGateway`、`ToolExecutor` 与 `RuntimeMiddleware` 不承担 Snapshot 逻辑。`runtime/**` 继续不依赖 Spring、JPA、Jackson、Redis、Reactor、WebClient 或 Flyway。

## 与恢复检查点的区别

Execution Configuration Snapshot 描述“本次 Run 以什么配置执行”，是不可变的审计输入。P10 Recovery Checkpoint 将描述“执行已经进行到哪里以及如何恢复”，是执行进度状态。两者身份、生命周期和用途不同：

```text
P9.3 Snapshot != P10 Recovery Checkpoint
```

## 暂缓事项

P9.4 Eval、P10 Recovery/Resume、Memory、Skills、RAG policy、配置 UI、Vault、分布式配置、Snapshot rollback，以及 prompt/skill/retrieval version 均不属于 P9.3。
