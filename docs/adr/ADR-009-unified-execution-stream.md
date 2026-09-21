# ADR-009：统一执行流

## 状态

P8.3 已接受。

## 决策

执行流是节点本地的实时执行活动投影，不是 Runtime 事实，也不是持久历史。Runtime 事实仍是 `AgentEvent`；流式层仅用 `RuntimeEventPayload` 包裹同一个事实，不作修改。

一个开放的 `runId` 恰好只有一个 `streamSequence` 分配权威。Runtime 事件、Model Delta 和 Control 观测都进入 `ExecutionStreamPublisher`；各生产者绝不自行分配序号。`RunStreamState` 将序号分配、信封构造和订阅者入队串行化为同一个发布边界。因此，即使生产者并发，每位订阅者的交付顺序仍与 `streamSequence` 顺序相同。

`ExecutionStreamEvent.occurredAt` 表示 Payload 进入统一发布边界的时间。被包裹的 `AgentEvent` 保留其原始 `occurredAt` 和 `sequence`。Runtime `AgentEvent.sequence` 与实时 `streamSequence` 是刻意区分的两种顺序。

每位订阅者都有独立的有界队列与异步交付链。队列溢出只会断开该慢订阅者；事件不会被静默丢弃，发布过程也不会等待 SSE 客户端。订阅者回调、传输失败、序列化失败和断线都只属于观测失败，不能合成或改写 Runtime 终态事件或 `AgentRunResult`。

订阅仅支持实时事件：只观察订阅之后发布的内容。P8.3 不根据 `Last-Event-ID` 重放，不查询 MySQL 历史，也不持久化 `streamSequence`。显式的外层执行生命周期在执行前打开 Run Stream，在完整外层执行返回或抛出后关闭；仅有 Runtime 终态事件并不负责流的清理。

对于 P8.3，已关闭的持久化 `runId` 不会重新打开为第二代实时流。`streamSequence` 在该持久 Run 执行对应的唯一实时流生命周期中保持唯一。生产组合仅在 Runtime Context Contributor 中打开 Hub；该 Contributor 在 P6 唯一持久化准入之后、`RUN_STARTED` 之前运行。重复 `runId` 因而会在 Hub 打开前被准入拒绝。临时 Hub 本身不保留已关闭 ID；持久化 Run 唯一约束提供实际可执行的保护，无需无限增长的内存 Tombstone Set。P7 租约获取、P6 准入及其调用方可见的失败优先级继续由既有 Coordinator 处理。

独立的、需显式启用的生产入口 `/api/agent/runs` 将 P8.2 流式模型、P8.3 Bridge、P6 持久化及可选 P7 协调组合起来。完整外层执行返回或抛出后才关闭 Hub。现有 `/api/chat` 保持旧链路。客户端必须提供唯一 `runId`，并在执行请求进行期间并发连接 SSE；因为只支持实时观测，执行开始后才连接可能错过早期事件，包括 `RUN_STARTED`。

SSE 路由 `/api/agent/runs/{runId}/stream` 沿用应用 `/api/**` 的身份认证边界，同时要求持久化 Run 归属授权。归属来自 `agent_runs.user_id`，不保存在节点本地 Hub 中。普通客户端请求不存在的 `runId` 或不属于自己的 `runId` 时，均收到 `404 NOT_FOUND`，不能据此区分 Run 是否存在。归属校验必须先于 `hub.subscribe(runId)`；通过校验后到实际订阅之间若 Run 已关闭，同样返回 404，不尝试跨数据库与 Hub 建立原子事务。

SSE 断线只移除该订阅，不会请求取消、停止 `AgentRunner`、修改租约或改变执行控制状态。

## 部署限制

P8.3 实时流只在本节点有效。在具备分布式流路由之前，部署需要入口亲和性或感知持有节点的路由，确保 SSE 请求到达正在执行该 Run 的节点。Redis 协调和未来分布式取消都不提供跨节点实时流转发。

## 暂缓事项

取消接线与端点（P8.4）、分布式控制（P8.5）、重放、恢复、持久化流存储、Redis Pub/Sub / Streams、Kafka 和 Model Gateway 工作，均不属于 P8.3。
