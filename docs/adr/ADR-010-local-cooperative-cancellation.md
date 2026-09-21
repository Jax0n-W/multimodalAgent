# ADR-010：单节点协作式取消

## 状态

P8.4 已实现；分布式取消和 Provider 主动中止仍待后续阶段。

## 决策

取消是针对一次执行的单调控制意图，不是中断线程、断开 HTTP、终止正在运行的模型或工具，也不是直接修改持久化 Run 状态。Runtime 在四个安全边界观察同一个 `CancellationContext`：模型调用前 `BEFORE_MODEL`、模型及 P6/P7 后置检查成功返回后 `AFTER_MODEL`、工具参数与策略判定为 `ALLOW` 后且 `TOOL_STARTED` 前 `BEFORE_TOOL_EXECUTION`、工具及 P6/P7 后置检查成功返回后 `AFTER_TOOL_EXECUTION`。一旦观察到取消，Runtime 不再开始未来的模型或工具工作，发出既有的 `RUN_STOPPED(CANCELLED)`；P6 将它持久化为 `AgentRunStatus.CANCELLED`。

已经开始的操作必须保留真实终态。模型完成或失败分别保留 `MODEL_COMPLETED` 或 `MODEL_FAILED`；工具完成或失败分别保留 `TOOL_SUCCEEDED` 或 `TOOL_FAILED`。已成功模型的 token usage、已成功工具的结果消息及 `toolsUsed` 不因之后的取消而丢失。已经开始的模型/工具若真实失败，既有 `MODEL_ERROR`/`TOOL_ERROR` 及 P6/P7 调用方可见失败优先级不被取消意图改写。策略 `DENY` 和 `REQUIRE_APPROVAL` 也保留 `POLICY_BLOCKED` 和 `WAITING_APPROVAL` 的治理语义。

生产执行使用一个节点本地活动控制表，以 `runId` 定位本 JVM 正在执行的控制对象。P6 持久化准入成功后、`RUN_STARTED` 前注册；外层执行无论返回还是抛出都清理。该对象同时注入 `AgentRuntimeContext.cancellationContext`，Runtime Core 不查询控制表、Spring、数据库或流式设施。`ExecutionControlState` 仅表示 `RUNNING` / `CANCEL_REQUESTED` 意图；活动表条目的 `ACTIVE` / `CORE_TERMINAL` / `CLOSED` 生命周期与之分开。控制表不保存历史 tombstone，也不承担 Run 身份、归属或状态的持久化真相。

取消请求与普通完成的终态决定在同一条目锁上序列化。普通完成先封口，之后的请求得到 `ALREADY_TERMINAL`；取消先被接受，普通完成不能再产生 `RUN_COMPLETED`，而在安全边界转为 `CANCELLED`。真正已开始的操作失败仍按其失败事实处理。`ACCEPTED` 只有一次；同时或重复请求得到 `ALREADY_REQUESTED`。条目已关闭或当前节点没有活动执行时不保留本地终态：服务重新读取持久状态，已终结的 Run 返回 `ALREADY_TERMINAL`，仍在运行但不在本节点及 `WAITING_APPROVAL` 返回 `NOT_ACTIVE`。P8.4 不把等待审批的未来恢复预先取消。

显式控制入口为 `POST /api/agent/runs/{runId}/cancel`。先用已认证用户与 `agent_runs.user_id` 核验归属；未知 Run 与非归属用户统一返回 404。`ACCEPTED`、`ALREADY_REQUESTED`、`ALREADY_TERMINAL` 返回 200 和结果 DTO；`NOT_ACTIVE` 返回 409。知道 `runId` 不构成授权。SSE 断开仅取消订阅，不调用此入口，也不改变执行控制。

首次 `ACCEPTED` 通过既有 `ExecutionStreamPublisher` 发出一次 `CONTROL_EVENT(CANCEL_REQUESTED)`。Runtime 事件、模型增量和该 Control 观测共享 P8.3 同一个 `runId` 序号权威；Control 生产者不分配序号。ControlEvent 表示取消意图的实时观测，`RUN_STOPPED(CANCELLED)` 才表示 Runtime 实际在安全边界停止，二者可能有时间差。流发布失败不能撤销已接受的取消，也不能改写 Core 结果。

## 部署限制与暂缓事项

活动控制表仅在当前 JVM 有效。执行在节点 A、取消请求到达节点 B 时，即使持久 Run 存在，节点 B 也返回 `NOT_ACTIVE`。在 P8.5 分布式控制完成前，入口必须具备亲和性或感知执行节点的路由。P8.4 不引入 Redis 控制键、跨节点消息、租约所有权修改、Provider abort、线程中断、`Future.cancel(true)`、Pause/Resume 或恢复机制。
