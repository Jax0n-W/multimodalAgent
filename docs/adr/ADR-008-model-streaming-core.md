# ADR-008：模型流式调用内核

- 状态：P8.2 已接受
- 日期：2026-09-18

## 背景

Runtime Core 每次模型调用消费一个完整的 `ModelTurn`。P8.1 引入了仅供观测的 `ModelDelta`，但刻意没有连接提供方流、为实时观测分配序号，或改变 Agent 控制流。

Spring AI 1.0.0 在两个层次暴露 OpenAI 兼容流式能力。原始协议包含带提供方 ToolCall Index 的增量 `ChatCompletionChunk.delta` 字段；既有的 `OpenAiApi.chatCompletionStream()` 辅助方法会先合并 Function Call Chunk 再返回，合并过程无法保留每个片段的 Index，还会拒绝部分多调用 Chunk 形状。这个转换后的表示无法满足 P8.2 严格的交错 ToolCall 关联契约。

## 决策

P8.2 在 Adapter 层增加使用 Spring WebClient 和 Spring AI 请求/响应 DTO 构建的 OpenAI 兼容 SSE 客户端，不引入其他提供方 SDK。Reactor 与 WebClient 只留在 `agent.adapter`；Runtime Core 不依赖它们。

Adapter 生命周期：

```text
AgentRunner
  -> AgentModel.generate(request)
  -> OpenAI 兼容流式请求
  -> 原始 SSE Delta 事件
  -> StreamingTurnAccumulator
       -> 有序文本累积 + ModelDelta 观测
       -> 按 Index 增量组装 ToolCall
       -> 捕获提供方 Usage
       -> 必需的结束原因 + 必需的 [DONE]
  -> 一个完整 ModelTurn
  -> AgentRunner
```

一次提供方流式请求仍只算一次 Agent 模型调用，只占一个 Runtime 轮次槽位，不受 Chunk 数量影响。`AgentRunner`、`AgentModel` 和 `ModelTurn` 不变。

### 文本观测

每个非空提供方文本片段都原样追加，不执行 trim，并作为一个 `ModelDelta` 提供给观察者。只包含空白字符的片段必须保留；不为真正的空片段伪造观测。工具参数片段只是内部组装状态，不能当作文本发出。

`ModelDeltaObserver` 仅用于观测。Adapter 会捕获观察者的 Runtime 异常，记录诊断警告，禁用本次调用后续观测，同时继续重建提供方结果。观测失败不能变成 `MODEL_FAILED`。

`StreamingModelInvocationScope` 是透明 Runtime Middleware。Run 将 Observer 注册在自己的 `AgentRuntimeContext` 中；进入模型调用时，Scope 把轮次和该 Observer 放入调用方线程的 `ThreadLocal`。Adapter 在订阅提供方之前恰好读取一次 Scope，并将两者捕获到不可变的局部引用。Reactor 回调只使用该引用，绝不读取 `ThreadLocal`。`finally` 块在成功或失败后清理 Scope；嵌套调用时恢复此前值，避免线程池复用时读到过期调用状态。它不改变 Runtime 模型请求或结果。P8.2 不分配 `streamSequence`；该权威属于 P8.3。

### ToolCall 组装

原始 OpenAI 兼容协议按片段传输。片段按提供方给出的 ToolCall Index 分组、独立累积，最终按提供方 Index 顺序返回。提供方 ID 原样累积和保留，不生成 UUID，也不从 Index 推导 ID。

只有在流提供受支持的终结 Finish Reason 和 `[DONE]` 后，组装器才要求完整的 ID、名称、类型和参数。要求 `[DONE]` 是当前受支持的 Ollama OpenAI 兼容 SSE Adapter 的封帧契约，不宣称所有 OpenAI 兼容提供方都遵守此规则。参数解析为既有 Runtime `Map<String, Object>`。缺失 ID、名称或 Index，不支持的类型，或畸形 JSON，都会使模型调用封闭式失败。部分 ToolCall 绝不能进入 `ToolExecutor`；既有 P3 的查找/反序列化/验证/Policy/执行仍是唯一工具执行链。

### 完成与失败

`STOP` 产生一个完整文本 `ModelTurn`；`TOOL_CALL` 和 `TOOL_CALLS` 产生一个完整工具调用 `ModelTurn`。其他结束原因、缺失结束原因、缺失 `[DONE]`、畸形 Frame、组装失败及提供方传输错误都会使模型调用失败。此前观测到的部分 Delta 不能把不完整的流变成成功。

只有提供方发送 Usage Chunk 时才捕获 Usage，绝不估算。若提供方没有 Usage，P8.2 使用已冻结 `ModelTurn` 契约要求的 `TokenUsage.ZERO` 回退；它不能被表述或报告为“提供方测量所得”。OpenAI 兼容协议定义唯一的权威最终 Usage Chunk，因此重复的 Usage Chunk 会封闭式失败，而不是静默覆盖此前计数。因此 `TokenUsage.ZERO` 也可能表示“提供方 Usage 不可用”，不能解释成提供方测量结果为零。“未知/已测量”的一等区分留待 P9 Usage / Budget 语义。

### 启用方式

Adapter 和 Client 需要显式构造。P8.2 不替换既有默认非流式模型路径。真实 Ollama 流式 Smoke Test 标记为 `real-model`，默认 Maven / CI 不执行。

P8.2 通过协议级原始 SSE Fixture 验证 ToolCall 流式处理。Fixture 经真实 WebClient Parser、Accumulator 和部分调用组装器重放，覆盖提供方 Index、交错调用、分片名称与参数、提供方 ID 保留，以及构造一个完整 `ModelTurn.TOOL_CALLS`。这是确定性的 Wire Protocol 证据，不等于声称实时微调模型对每个 Prompt 都会选择工具。活提供方 ToolCall 行为仍是模型/后端可靠支持时才启用的兼容性检查。

## 暂缓事项

P8.2 不实现流序号分配、StreamHub、面向客户端的 SSE 交付、订阅者、背压、断线重连/重放、Runtime 取消检查点、提供方请求终止、活跃执行 Registry、Redis 控制标记、Pub/Sub、分布式取消、暂停、恢复、重试、Recovery、接管或外部隔离。

提供方调用 Deadline 和流式超时语义留待 P9 Model Gateway。P8.2 刻意不增加 Reactor Timeout Operator 或定时阻塞，因为它们会引入新的模型失败语义。
