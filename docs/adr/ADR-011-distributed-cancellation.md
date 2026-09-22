# ADR-011：分布式取消投递

## 状态

P8.5 已实现；本 ADR 只冻结跨节点取消投递，不改变 P8.4 的执行语义。

## 决策

当取消请求到达非执行节点时，请求节点先依据 `agent_runs.user_id` 验证持久化归属，再尝试 P8.4 本地活动表。若本地活动条目返回 `ACCEPTED`、`ALREADY_REQUESTED` 或 `ALREADY_TERMINAL`，直接返回该结果，不访问 Redis。若本地没有活动条目，重新读取持久状态：永久终态返回 `ALREADY_TERMINAL`，`WAITING_APPROVAL` 返回 `NOT_ACTIVE` 且不广播；其余非终态才进入分布式投递。

分布式投递使用独立于 P7 租约键和值的 Redis Pub/Sub command 与 ACK 两个通道。请求节点先将随机 `commandId` 对应的等待对象放入本地 pending 表，再发布包含 `commandId`、`runId` 的命令。所有节点监听命令；只有拥有该 `runId` 本地活动条目的节点调用 `LocalExecutionControlRegistry.requestCancel(runId)` 并发出带相同 `commandId`、`runId` 及结果的 ACK。`NOT_ACTIVE` 节点保持沉默，不 ACK。请求节点仅接受与 pending 命令及 Run 身份匹配、且结果非 `NOT_ACTIVE` 的 ACK；超时、发布失败与完成后移除 pending，迟到 ACK 忽略。

**Redis 发布成功绝不等于取消已接受。** `ACCEPTED`、`ALREADY_REQUESTED`、`ALREADY_TERMINAL` 只来自执行节点对 P8.4 活动条目的真实判定。因此普通完成与取消的线性化边界仍由 P8.4 条目承担：终态先封口则 ACK 为 `ALREADY_TERMINAL`；取消先被接受则正常完成不能覆盖它。P8.5 不分配新的 `ControlEvent` 或执行流序号。首次 `ACCEPTED` 产生的唯一 ControlEvent 仍由执行节点 P8.4 发布。

远端 Redis 发布不可用时，接口返回 503，不伪装成成功或 `NOT_ACTIVE`。命令发出但未收到 ACK 时，结果是不确定的：重新读取持久 Run；若已为 `COMPLETED`、`FAILED` 或 `CANCELLED`，返回 `ALREADY_TERMINAL`，否则返回 503。客户端可以重试，因为 P8.4 取消意图单调且幂等；重试可能得到 `ALREADY_REQUESTED` 或 `ALREADY_TERMINAL`。Redis Pub/Sub 不是可靠队列，P8.5 不承诺消息持久化、重放或恰好一次投递。

P7 `RunLeaseSession` 仍决定执行资格，P8.4 `ExecutionControl` 决定取消意图，`AgentRunResult` 记录 Core outcome。P8.5 只送达控制命令，不修改租约 schema、Runtime 四个 checkpoint、已启动模型/工具的真实完成或失败优先级，也不让 Runtime Core 依赖 Redis。

## 暂缓事项

Provider 主动中止、线程中断、Pause/Resume、分布式 SSE、流重放、Redis Streams、Kafka、可靠队列、P9 与 P10 均不属于 P8.5。
