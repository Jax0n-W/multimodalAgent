# Unified Agent Runtime：自主 Tool Calling

生产 Runtime 现在使用有界 ReAct 循环，而不是把知识检索限定为一次固定前置步骤。

## 循环状态

```text
MODEL_DECIDE
  ├─ STOP ───────────────────────────────> COMPLETE
  ├─ TOOL_CALLS -> VALIDATE -> POLICY
  │                  ├─ DENY ────────────> POLICY_BLOCKED
  │                  ├─ APPROVAL ────────> WAITING_APPROVAL
  │                  └─ ALLOW -> BUDGET -> TIMEOUT-SANDBOX -> OBSERVATION
  │                                                        └─> MODEL_DECIDE
  └─ 最后一个模型步骤：tools=[] + RUNTIME_FINALIZATION_V1 -> COMPLETE
```

- `maxIterations` 的最后一步被保留为无工具的收尾步骤；模型只能根据已有 Observation 作答。
- 参数错误、未知工具和工具超时在 autonomous policy 下作为 Observation 返回，模型可改参或选择其他工具。
- 策略拒绝、等待审批、取消、不可验证预算仍然立即停止。
- 若模型/Token 预算已无空间执行收尾调用，Runtime 返回明确的“不完整”收尾文本，不伪造未取得的结果。

## 注册工具

| Tool | 输入契约 | 风险/审批 | 默认超时 | 恢复语义 |
|---|---|---|---|---|
| `search_campus_policy` | 查询词、topK | LOW | 读超时 | REPLAY_SAFE |
| `search_counseling_resources` | 需求、topK | LOW | 读超时 | REPLAY_SAFE |
| `query_consultant_schedule` | 日期区间、形式、专长、limit | MEDIUM | 读超时 | REPLAY_SAFE |
| `query_my_recent_assessments` | limit | MEDIUM | 读超时 | REPLAY_SAFE |
| `submit_crisis_ticket` | 摘要、紧急度、联系许可 | HIGH，必须审批 | 写超时 | IDEMPOTENT |

Spring AI 的 `JsonSchemaGenerator` 从各 Tool 的 record 输入类型生成 provider JSON Schema。服务端随后使用严格 Jackson 映射（拒绝未知字段和标量强制转换）及 Jakarta Validation 再校验一次。模型参数中不允许出现 `userId`、`sessionId`、`snapshotId` 或 `toolCallId`；这些身份由 `ToolExecutionContext` 从 Runtime 注入。

## 预算、超时和不可变快照

每个调用按以下固定顺序执行：

1. 从本次 `AgentRunSpec.allowedTools`（已写入 Execution Config Snapshot）检查白名单；
2. 强类型参数校验；
3. 风险策略与人工审批；
4. `BudgetSession.admitToolCall()` 原子扣减工具调用预算；
5. 在有界 Tool Executor 中运行，并使用 Tool Descriptor 的超时终止等待；
6. 记录可靠结果后才把 Observation 返回模型。

危机工单的主键由 `runId + toolCallId` 确定，重复恢复不会创建第二张工单；记录同时保存 `runtimeConfigSnapshotId`，可追溯执行所使用的不可变配置。

## Prompt 注入隔离

所有标记为 `UntrustedEvidenceTool` 的输出在 Runtime 中统一编码为：

```text
UNTRUSTED_EVIDENCE_DATA_V1
{"schemaVersion":1,"kind":"...","source":"tool:...","toolName":"...","content":"..."}
```

`content` 始终是 JSON 字符串，不能注入或覆盖信封元数据；整个信封只会作为 `TOOL` 消息回传，绝不会成为 `SYSTEM` 消息。原有 RAG 与多模态输入继续经过同一协议的初始上下文信封。

## 配置

```yaml
multimodal-agent:
  runtime:
    enabled: true
    tools:
      read-timeout: 5s
      mutation-timeout: 10s
    budget:
      max-model-calls: 4
      max-tool-calls: 6
      max-total-tokens: 12000
```

环境变量分别为 `AGENT_TOOL_READ_TIMEOUT`、`AGENT_TOOL_MUTATION_TIMEOUT`、`AGENT_BUDGET_MAX_MODEL_CALLS`、`AGENT_BUDGET_MAX_TOOL_CALLS` 和 `AGENT_BUDGET_MAX_TOTAL_TOKENS`。

排班查询读取本地表 `consultant_schedule_slots`；表为空时返回显式 `noConfiguredAvailability=true`，不会生成假排班。危机工单写入 `crisis_tickets`，只有批准后的 Tool Call 才能落库。

代码保持 Java 17 源级兼容，因此可直接运行在 Java 21；若项目整体切换到 Java 21，可把当前有界平台线程执行器替换为受并发闸门约束的虚拟线程执行器，Tool 契约无需变化。
