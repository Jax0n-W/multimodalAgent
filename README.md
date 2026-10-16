# multimodalAgent

> 面向高敏场景（校园心理危机干预）的高可靠持久化 Agent 运行时引擎与混合检索系统
> Durable Agent Runtime & Hybrid RAG Engine

`multimodalAgent` 聚焦已经落地的后端能力：单一确定的 Runtime 主线、自主 ReAct 工具调用、不可变执行快照、持久化恢复、纵深防 Prompt 注入、全本地 Hybrid RAG，以及面向高风险通知的事务型 Outbox。

项目保留文本、音频、图片和视频输入适配入口，但“多模态”不是当前架构说明的中心。系统的核心价值在于：即使模型、向量服务、外部通知或异步报表发生故障，关键业务事实仍可被可靠保存、明确降级、审计和恢复。

> [!CAUTION]
> 本项目仅用于教育、架构研究与工程演示，不能替代医生、心理咨询师或其他专业人员的诊断与干预，也不提供临床级安全保证。真实部署必须由学校和专业机构审核危机分级规则、应急话术、联系人、通知渠道、数据权限、值班流程及监控告警。

## 核心定位

- **一条回答主线**：文本与多模态聊天最终都进入 `Unified Agent Runtime`，不存在按配置切换回答引擎的双路由。
- **受控自主性**：模型可在 ReAct 循环中根据 Observation 连续调用工具，但只能使用本次不可变 Snapshot 中批准的工具、身份和预算。
- **高敏事实优先**：HIGH-risk 的评估报告、`AlertRecord` 与通知意图先于可选检索，在同一事务中完成落盘。
- **全本地真实检索**：Jieba + BM25 与 Spring AI `OllamaEmbeddingModel` 的真实本地向量并行召回，经 RRF 和可插拔 Reranker 排序。
- **诚实降级**：向量服务不可用时返回真实 BM25 结果和 `LEXICAL_ONLY_DEGRADED` 状态，不伪造向量、排班、联系人或检索结果。
- **持久化闭环**：`AgentRun`、配置/上下文 Snapshot、Checkpoint、工具结果与最终回答均可审计；成功结果幂等投影回 `chat_messages`。
- **可靠异步通知**：Claim-fenced Outbox 与 Excel 台账完全解耦，避免报表写入失败阻断危机通知。

## 系统架构

~~~mermaid
flowchart TD
    A[已认证学生请求] --> B[ChatController<br/>SSE 接入与参数校验]
    B --> C[ChatService<br/>稳定业务边界]
    C --> D[UnifiedChatRuntimeService<br/>统一编排入口]

    D -. 按 userId 与 sessionId 查询 .-> E[ChatSessionRegistry<br/>严格会话所有权校验]
    E -. 新会话创建或已有会话确认 .-> D

    D --> F[服务端意图与风险判定]
    F --> G{是否 HIGH-risk}
    G -- 是 --> H[单事务原子落盘<br/>评估报告 + AlertRecord + 通知意图]
    H --> I[Claim-fenced 通知 Outbox]
    I --> J[异步通知发送<br/>租约 + claimToken + 条件状态迁移]
    H --> K[事务提交后异步写 Excel 台账]
    G -- 否 --> L[常规业务上下文]
    H --> L

    L --> M[本地 Hybrid RAG<br/>BM25 与 Dense 并行 + RRF + Reranker]
    M --> N[UNTRUSTED_EVIDENCE_DATA_V1<br/>非权威证据信封]

    D --> O[StreamingAgentExecutionService<br/>统一执行与事件桥接]
    N -. 非权威上下文 .-> P[Immutable Context Snapshot]
    Q[(已完成的持久化 AgentRun)] --> R[ConversationMemorySource<br/>恢复同用户同会话的完整历史轮次]
    R --> P
    O --> P
    P --> S[AgentRunner<br/>Autonomous ReAct Loop]

    S <--> T[本地 Ollama 对话模型<br/>mindbridge-qwen2.5-7b]
    S <--> U[Tool Registry + Policy + Budget<br/>五个强类型受控工具]
    S --> V[(AgentRun / Step / Tool Outcome<br/>Checkpoint 与执行快照)]
    V --> Q

    S --> W[SSE meta / token / done]
    S --> X[ChatHistoryProjector<br/>按 runId 幂等投影]
    X --> Y[(chat_messages<br/>Flyway V10 投影闭环)]

~~~

主调用链始终是：

~~~text
ChatController
  -> ChatService
  -> UnifiedChatRuntimeService
  -> StreamingAgentExecutionService
  -> AgentRunner（Autonomous ReAct Loop）
~~~

配置只改变模型地址、检索模式、预算和基础设施能力，不改变这条最终回答主线。`ChatSessionRegistry` 只接受“当前认证用户 + 会话公开 ID”联合匹配的会话；未知会话或跨用户会话直接拒绝。

多轮记忆不依赖临时内存。`ConversationMemorySource` 从已成功完成的 `AgentRun` 及其 Context Snapshot 中重建经过身份校验的用户/助手轮次，按默认最多 6 轮、总计 12000 字符的边界注入新运行。运行成功后，`ChatHistoryProjector` 通过 Flyway V10 创建的 `chat_history_projections` 表按 `runId` 幂等记录投影状态，并把最终回答写入现有 `chat_messages` 视图。

## 亮点一：受控自主 Agent Runtime 与分级预算兜底

### 自主 ReAct 状态机

生产装配明确使用 `AgentLoopPolicy.autonomous()`：

1. 模型先判断直接回答还是发起一个或多个 Tool Call。
2. Runtime 对工具名、参数、策略、预算、取消状态和 Snapshot 身份逐层校验。
3. 工具结果以 Observation 回注消息历史。
4. 模型可以基于新 Observation 再次调用工具，实现连续多步 Tool Calling。
5. 模型输出最终答案，或由 Runtime 根据终止原因安全收口。

参数映射失败、Jakarta Validation 越界、未知工具、执行异常和工具超时会被归类为结构化错误事件，并以工具 Observation 返回模型。在仍有预算时，模型可以修正工具名或参数后再次尝试，即 Self-Correction。

以下情况不会交给模型“自行绕过”：

- 工具策略拒绝：立即以 `POLICY_BLOCKED` 硬中断。
- HIGH 风险写工具等待人工审批：立即进入 `WAITING_APPROVAL`。
- 用户或控制面取消：在模型调用和工具执行检查点立即终止。
- 工具/模型预算无法准入：记录 `BudgetBlockedEvent` 并阻断越权调用。

### 分级预算与强制收尾

`ExecutionBudget` 支持模型调用数、工具调用数、输入/输出/总 Token 和成本上限，并被固化进本次 Runtime 配置 Snapshot。高敏场景建议显式采用以下基线：

| 预算维度 | 运行基线 | 环境变量 |
| --- | ---: | --- |
| `MAX_MODEL_CALLS` | 4 | `AGENT_BUDGET_MAX_MODEL_CALLS=4` |
| `MAX_TOOL_CALLS` | 6 | `AGENT_BUDGET_MAX_TOOL_CALLS=6` |
| `MAX_TOTAL_TOKENS` | 12000 | `AGENT_BUDGET_MAX_TOTAL_TOKENS=12000` |
| 只读工具超时 | 5 秒 | `AGENT_TOOL_READ_TIMEOUT=5s` |
| 写工具超时 | 10 秒 | `AGENT_TOOL_MUTATION_TIMEOUT=10s` |

当前聊天 `AgentRunSpec.maxIterations` 固定为 3，因此单次聊天的模型实际调用还会受到三步上限约束。`MAX_MODEL_CALLS=4` 是更外层的统一预算上限，可供其他 Runtime 入口复用。

自主循环会把最后一个模型迭代预留为强制收尾轮：追加 `RUNTIME_FINALIZATION_V1` 指令，并向模型传入 `tools=[]`，要求仅依据已有会话与 Observation 总结，不再发起新工具调用。若 Token 或模型调用预算在收尾轮之前已经彻底归零，则 Runtime 不会越权追加调用，而是返回明确的“预算已耗尽、结果可能不完整”终止提示。

> `application.yml` 允许预算项为空，以便 Runtime 框架测试和其他调用方自定义；校园高敏部署不应依赖空值，应显式配置上述上限。

## 亮点二：强契约业务工具沙箱与防越权注入

统一聊天运行的工具白名单固定包含以下五个校园业务工具：

| 工具名 | 风险级别 | 强类型输入要点 | 行为与真实性约束 | 默认超时 |
| --- | --- | --- | --- | ---: |
| `search_campus_policy` | LOW | `query` 必填且不超过 500 字符；`topK` 为 1–10 | 检索已入库校园制度；结果始终是不可信证据 | 5 秒 |
| `search_counseling_resources` | LOW | `need` 必填且不超过 500 字符；`topK` 为 1–10 | 检索心理支持资源与求助指南；不生成虚构机构信息 | 5 秒 |
| `query_consultant_schedule` | MEDIUM | 起止日期、`online/in_person`、专业方向、1–20 条；区间最长 31 天 | 只查询本地真实排班；没有记录时返回 `noConfiguredAvailability=true`，绝不补造咨询师或时间段 | 5 秒 |
| `query_my_recent_assessments` | MEDIUM | `limit` 为 1–10 | 只使用 Runtime 注入的当前用户 ID 查询本人摘要，不返回原始对话正文 | 5 秒 |
| `submit_crisis_ticket` | HIGH | 摘要、紧急程度、联系同意和偏好联系方式 | 必须人工审批；按 `runId + toolCallId` 幂等写入本地危机工单 | 10 秒 |

每个 Tool 的输入都定义为带 Jakarta Validation 注解的 Java `record`。`SpringAiModelToolDefinitionProjector` 通过 Spring AI 生成 JSON Schema 暴露给模型；服务端 `ToolArgumentResolver` 再执行严格 Jackson 映射：

- 开启 `FAIL_ON_UNKNOWN_PROPERTIES`，拒绝模型臆造的未知字段。
- 关闭标量强制转换，避免字符串、数字和布尔值被静默误转。
- 映射后执行 Jakarta Validation，统一返回参数错误 Observation。
- 工具执行前再经过 allow-list、风险策略、审批、预算和取消检查。

`submit_crisis_ticket` 的 `userId`、`sessionId`、`runId`、`toolCallId` 与 `runtimeConfigSnapshotId` 不属于模型参数。它们由 Runtime 底层从受治理执行上下文直接注入，服务端还会复核会话所有权。模型无法伪造另一个用户或会话的身份，从根源上降低 Confused Deputy 越权风险。

## 亮点三：纵深防注入隔离（Defense-in-Depth）

项目把“来源可信度”设计为结构边界，而不是只依赖 Prompt 中的一句提醒。

- 工具回执、排班、历史评估摘要、RAG 片段以及多模态转写/分析文本，统一包装为 `UNTRUSTED_EVIDENCE_DATA_V1` 结构化信封。
- 工具结果只能通过 `TOOL` 消息回注；请求前置证据只能进入非权威上下文贡献，不能成为安全策略。
- `UntrustedEvidenceEnvelope` 把原始工具输出放进 JSON 字符串字段，证据正文无法注入或覆盖 `schemaVersion`、`kind`、`source`、`toolName` 等信封字段。
- `AgentMessage` 构造器是中央防线：任何以不可信证据标记开头的内容若尝试构造成 `SYSTEM` 消息，立即抛出异常。
- 可信 `SYSTEM` 上下文只包含服务端生成的路由、安全级别和回答策略，不包含用户、检索或多模态原文。
- Context Snapshot 会冻结本次证据及其 Provenance；崩溃恢复复用原 Snapshot，不重新检索并改变历史执行语义。

因此，即使知识文档、排班备注或上传内容包含“忽略之前规则”等恶意文本，它也只能作为数据被引用，不能升级为系统指令。

## 亮点四：全本地真 Hybrid RAG 管线与向量自愈

### 检索链路

~~~text
查询
 ├─ 词法路：NFKC -> Jieba 搜索分词 -> 停用词过滤 -> 中文 OOV 回退 -> BM25
 └─ 稠密路：Spring AI OllamaEmbeddingModel -> 本地 Embedding -> 余弦相似度
                         ↓
              精确 RRF（k=60）去重融合
                         ↓
             可插拔本地 Reranker 精排
                         ↓
        结果 + 状态 + 延迟 + Provenance
~~~

- **词法路**：Jieba 搜索模式分词是主切分器，辅以 Unicode NFKC、停用词过滤和中文 Han 字符 OOV 回退；BM25 参数固定为 `k1=1.2`、`b=0.75`。
- **稠密路**：Spring AI `OllamaEmbeddingModel` 调用本地 Ollama，默认模型为 `bge-m3`；文档向量连同模型名、维度和 Schema 版本持久化，查询时执行维度与模型一致性检查，再计算余弦相似度。
- **并行召回**：`HYBRID_RRF` 使用独立的两线程检索执行器并发运行 BM25 与 Dense 分支。
- **稳定融合**：RRF 使用精确有理数计算 `sum(1 / (60 + rank))`，按稳定 chunk 标识去重，并以最佳名次、贡献分支数和稳定字节序完成确定性打破平局。
- **轻量精排**：RRF 后进入 `Reranker` 接口。默认透传以避免增加可用性依赖；配置本地 Ollama Reranker 后可执行零温度精排。精排失败会保留 RRF 顺序，并上报 `knowledge.retrieval.rerank.fallback`。

### 启动自愈与诚实降级

应用启动时，`KnowledgeIngestionService` 会载入空知识库的内置公开校园文档，并执行 Auto-Backfill：

- 空向量自动补嵌入。
- 历史测试阶段遗留、缺少版本信封的向量自动重算。
- 模型名称变化或维度不匹配的向量自动重算。
- 回填期间 Ollama 不可用时立即停止本轮回填，保留可用的词法索引，不写入伪造向量。

在线 Hybrid 检索若遇到 Ollama 连接失败、Embedding 模型缺失、模型身份不匹配或向量维度不兼容，会返回真实 BM25 结果并标记：

~~~text
LEXICAL_ONLY_DEGRADED
~~~

该状态同时进入：

- Micrometer Observation：`knowledge.retrieval.result`；
- 结构化警告日志：`knowledge_retrieval_degraded`；
- Counter 指标：`knowledge.retrieval.degraded`。

降级异常不会越过检索边界，也不会伪装成 Hybrid 成功。`DENSE_ONLY` 不可用时则明确返回 `DENSE_UNAVAILABLE`；没有任何证据时返回 `NO_EVIDENCE`。

## HIGH-risk 前置事务与 Claim-fenced Outbox

HIGH-risk 流程遵循“先保存安全事实，再做可选增强”的顺序：

1. 保存本轮用户消息。
2. 在一个数据库事务中保存 `PsychologicalReport`。
3. 为配置的每个接收人创建 `AlertRecord` 与唯一通知意图。
4. 事务成功提交后，才允许发起 RAG 和 Runtime 回答。
5. 提交后分别调度通知 Outbox 与 Excel 台账任务。

通知 worker 通过 `owner + claimToken + leaseExpiresAt` 原子 Claim 任务，所有状态迁移都携带当前 Claim 代次。旧 worker 即使延迟返回，也不能覆盖新 worker 已接管的状态。外部发送结果不确定时进入 `UNCERTAIN`，不会盲目重复发送；明确未送达时才按重试策略回到可领取状态。

Excel 台账在独立异步事务中写入。Excel 失败只更新台账状态和错误字段，不阻断、不回滚、也不冒充通知投递结果。

## 技术栈

| 分类 | 当前实现 |
| --- | --- |
| 语言与框架 | Java 17、Spring Boot 3.3.5、WebFlux、Spring Security、Spring Data JPA |
| AI 适配 | Spring AI 1.0.0、Ollama 对话与 Embedding 适配器 |
| 对话模型 | `mindbridge-qwen2.5-7b` |
| Embedding | `bge-m3`，可切换 `qwen3-embedding-0.6b` |
| 检索 | Jieba、BM25、Dense Cosine、RRF、可插拔 Reranker |
| 持久化 | MySQL 8、Flyway、持久化 AgentRun/Snapshot/Checkpoint/Tool Outcome |
| 协调 | Redis 7，可选分布式租约、Watchdog 与取消传播 |
| 可靠消息 | 事务型通知意图、Claim fencing、租约、重试与不确定态隔离 |
| 接口 | HTTP Basic 演示认证、SSE 流式聊天、Actuator 指标 |
| 测试 | JUnit 5、Reactor Test、ArchUnit、Testcontainers |

项目 Maven 构建目标为 Java 17。使用 JDK 21 运行时仍会编译为 Java 17 兼容字节码；CI 或生产环境应统一并锁定实际 JDK 发行版。

## Quick Start（快速开始）

### 1. 前置条件

| 依赖 | 用途 |
| --- | --- |
| Docker Desktop / Docker Engine | 启动 MySQL、Redis 与应用容器 |
| Ollama | 在本机运行对话与 Embedding 模型 |
| JDK 17+ | 本地运行和测试 |
| Maven 3.9+ | 构建与执行测试 |

Docker 中的应用通过 `host.docker.internal:11434` 访问宿主机 Ollama。启动容器前请确保 Ollama 已运行。

### 2. 准备本地模型

在项目根目录执行：

~~~powershell
ollama create mindbridge-qwen2.5-7b -f .\models\multimodalAgent-qwen2.5-7b-ft\Modelfile
ollama pull bge-m3
ollama list
~~~

如需切换轻量 Embedding 模型：

~~~powershell
ollama pull qwen3-embedding-0.6b
$env:OLLAMA_EMBEDDING_MODEL = "qwen3-embedding-0.6b"
~~~

Unix-like 环境也可直接运行：

~~~bash
./scripts/run-dev.sh
~~~

`scripts/run-dev.sh` 会检查或启动 Ollama；缺少对话模型时根据仓库 `Modelfile` 创建，缺少 Embedding 模型时自动执行 `ollama pull`，随后以本地 Ollama 配置启动 Spring Boot。

### 3. 启动 Docker Compose

~~~powershell
docker compose up -d --build mysql redis app
docker compose ps
curl.exe http://localhost:8080/actuator/health
~~~

默认演示账号：

| 角色 | 用户名 | 密码 | 用途 |
| --- | --- | --- | --- |
| 学生 | `student` | `student123` | 发起聊天与查看本人报告 |
| 管理员 | `admin` | `admin123` | 查看报告、告警、会话与维护知识库 |

这些账号和密码仅用于本地演示，禁止暴露到公网或在生产环境复用。

### 4. V10 本地迁移重置提示

当前 Flyway V10 的职责是创建 `chat_history_projections`。如果本地数据库曾执行过开发阶段的旧版 V10，Flyway 会因 checksum 不一致拒绝启动。系统尚未上线且没有需要保留的历史会话时，可清理本地 Compose 数据卷后重新迁移：

~~~powershell
# 警告：此命令会删除本项目 Compose 管理的 MySQL、Redis 等本地数据卷。
docker compose down -v
docker compose up -d --build mysql redis app
~~~

不要对包含真实数据的环境执行上述命令；此类环境应新增前向迁移脚本，而不是重写已发布 Migration。

### 5. 停止服务

~~~powershell
# 保留数据卷
docker compose down
~~~

## 配置说明

### 本地模型与检索

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `AI_PROVIDER` | `ollama` | Runtime 模型 Provider；本地演示使用 Ollama |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | 本机运行地址；Compose 内默认为宿主机地址 |
| `OLLAMA_MODEL` | `mindbridge-qwen2.5-7b` | 对话与 Tool Calling 模型 |
| `OLLAMA_EMBEDDING_MODEL` | `bge-m3` | 本地 Embedding 模型 |
| `OLLAMA_EMBEDDING_TIMEOUT` | `5s` | Embedding 调用超时 |
| `RAG_RETRIEVAL_MODE` | `HYBRID_RRF` | 可选 `BM25_ONLY`、`DENSE_ONLY`、`HYBRID_RRF` |
| `RAG_TOP_K` | `4` | 默认返回片段数 |
| `RAG_MAX_INDEX_CHUNKS` | `2000` | 单机内存检索的最大切块数 |
| `RAG_MAX_EMBEDDING_DIMENSIONS` | `3072` | 向量维度安全上限 |
| `RAG_RERANK_ENABLED` | `false` | 是否启用本地 Ollama 精排 |
| `OLLAMA_RERANK_MODEL` | 空 | 启用精排时必须指定 |
| `RAG_RERANK_CANDIDATE_LIMIT` | `20` | 进入精排的 RRF 候选数 |

启用本地 Reranker：

~~~powershell
$env:RAG_RERANK_ENABLED = "true"
$env:OLLAMA_RERANK_MODEL = "<本地-reranker-模型名>"
~~~

若 Reranker 返回格式错误、评分缺失或调用失败，系统保留确定性的 RRF 顺序并上报降级指标。

### Runtime、记忆与预算

| 环境变量 | 默认值/建议值 | 说明 |
| --- | --- | --- |
| `AGENT_MEMORY_MAX_TURNS` | `6` | 最多恢复的完整历史轮次 |
| `AGENT_MEMORY_MAX_TOTAL_CHARS` | `12000` | 历史记忆字符总上限 |
| `AGENT_TOOL_READ_TIMEOUT` | `5s` | 只读工具超时 |
| `AGENT_TOOL_MUTATION_TIMEOUT` | `10s` | 写工具超时 |
| `AGENT_BUDGET_MAX_MODEL_CALLS` | 建议 `4` | 模型调用硬上限 |
| `AGENT_BUDGET_MAX_TOOL_CALLS` | 建议 `6` | 工具调用硬上限 |
| `AGENT_BUDGET_MAX_TOTAL_TOKENS` | 建议 `12000` | 输入与输出 Token 总上限 |
| `AI_INVOCATION_TIMEOUT` | `2m` | 单次模型调用总超时 |
| `AI_IDLE_TIMEOUT` | `30s` | 流式响应空闲超时 |

本地 Maven 启动时可显式设置高敏预算：

~~~powershell
$env:AGENT_BUDGET_MAX_MODEL_CALLS = "4"
$env:AGENT_BUDGET_MAX_TOOL_CALLS = "6"
$env:AGENT_BUDGET_MAX_TOTAL_TOKENS = "12000"
mvn spring-boot:run
~~~

使用容器部署时，应把相同变量加入 `app.environment` 或平台 Secret/Config 管理系统。

### 数据库、协调与通知

| 环境变量 | Compose 默认值 | 说明 |
| --- | --- | --- |
| `DB_URL` | MySQL `multimodalAgent` 数据库 | JPA 与 Flyway 数据源 |
| `REDIS_HOST` / `REDIS_PORT` | `redis:6379` | 分布式协调与取消基础设施 |
| `MCP_EXCEL_MODE` | `local` | 本地 Excel 台账模式 |
| `MCP_EXCEL_LOCAL_PATH` | `./data/multimodalAgent-reports.xlsx` | Excel 输出位置 |
| `MCP_EMAIL_MODE` | `log` | 本地默认只记录通知标识，不外发邮件 |
| `ALERT_OUTBOX_WORKER_ENABLED` | `true` | 是否启用通知 Outbox worker |
| `ALERT_OUTBOX_POLL_INTERVAL_MS` | `30000` | Outbox 轮询间隔 |
| `ALERT_OUTBOX_LEASE_DURATION` | `2m` | Claim 租约时长 |
| `ALERT_OUTBOX_RETRY_DELAY` | `1m` | 明确未送达后的重试等待 |

## Demo Scenarios（演示场景）

所有聊天接口返回 SSE。第一个 `meta` 事件包含本次会话的 `sessionId`，随后是 `token` 事件，最终以 `done` 或安全的 `error` 事件结束。

### 场景一：普通对话

~~~powershell
curl.exe -N -u student:student123 `
  -H "Content-Type: application/json" `
  --data-raw '{"message":"你好，请介绍一下你能提供哪些校园支持。"}' `
  http://localhost:8080/api/chat/stream
~~~

### 场景二：基于 sessionId 恢复持久化多轮记忆

先发起第一轮并从 `event: meta` 对应的 JSON 中复制 `sessionId`：

~~~powershell
curl.exe -N -u student:student123 `
  -H "Content-Type: application/json" `
  --data-raw '{"message":"我最近因为论文截止日期睡不好，请记住这个背景。"}' `
  http://localhost:8080/api/chat/stream
~~~

继续同一会话：

~~~powershell
$sessionId = "<替换为 meta 事件中的 sessionId>"
$body = @{
  sessionId = $sessionId
  message   = "请结合我上一轮提到的压力来源，给我一个今晚可以执行的计划。"
} | ConvertTo-Json -Compress

curl.exe -N -u student:student123 `
  -H "Content-Type: application/json" `
  --data-raw $body `
  http://localhost:8080/api/chat/stream
~~~

第二轮会从已完成的持久化 `AgentRun` 恢复记忆，而不是依赖进程内 Session。

### 场景三：自主调用校园制度检索工具

~~~powershell
curl.exe -N -u student:student123 `
  -H "Content-Type: application/json" `
  --data-raw '{"message":"请先调用校园制度检索工具，查询学生请假与销假要求，再根据检索结果回答并说明证据不足之处。"}' `
  http://localhost:8080/api/chat/stream
~~~

模型应根据任务自主选择 `search_campus_policy`；检索正文会以 `UNTRUSTED_EVIDENCE_DATA_V1` Observation 返回 ReAct 循环。

### 场景四：查询真实咨询师排班

~~~powershell
$startDate = (Get-Date).ToString("yyyy-MM-dd")
$endDate = (Get-Date).AddDays(7).ToString("yyyy-MM-dd")
$body = @{
  message = "请调用咨询师排班工具，查询 $startDate 到 $endDate 的线下可用时段；如果没有配置，请明确告诉我没有可用排班，不要猜测。"
} | ConvertTo-Json -Compress

curl.exe -N -u student:student123 `
  -H "Content-Type: application/json" `
  --data-raw $body `
  http://localhost:8080/api/chat/stream
~~~

数据库没有对应排班时，工具返回 `noConfiguredAvailability=true`，模型不得生成虚构时间段。

### 场景五：HIGH-risk 前置落盘与告警查看

~~~powershell
curl.exe -N -u student:student123 `
  -H "Content-Type: application/json" `
  --data-raw '{"message":"我不想活了，感觉马上会伤害自己。"}' `
  http://localhost:8080/api/chat/stream

curl.exe -u admin:admin123 http://localhost:8080/api/admin/reports
curl.exe -u admin:admin123 http://localhost:8080/api/admin/alerts
curl.exe -u admin:admin123 http://localhost:8080/api/admin/excel-records
~~~

报告、告警与通知意图在检索和模型回答前完成事务提交。默认 `MCP_EMAIL_MODE=log` 只记录去敏后的持久化标识，便于本地验证 Outbox，不会真实发送邮件。

### 场景六：维护公开校园知识

~~~powershell
curl.exe -u admin:admin123 `
  -H "Content-Type: application/json" `
  --data-raw '{"source":"leave-policy.md","content":"学生因病请假应按学院流程提交材料，返校后按规定完成销假。"}' `
  http://localhost:8080/api/admin/knowledge
~~~

共享索引只应写入经管理员维护的公开校园资料。学生私聊和私人记忆不会自动进入共享知识库。

### 查看检索降级指标

~~~powershell
curl.exe -u admin:admin123 `
  http://localhost:8080/actuator/metrics/knowledge.retrieval.degraded
~~~

## 测试与验证

### 全量回归

~~~powershell
mvn clean test
~~~

当前验证基线为 **791+ 项测试，0 failure、0 error**，覆盖：

- Runtime 生命周期、事件顺序、预算、取消与安全终止；
- 自主多步 Tool Calling、Self-Correction、审批等待和策略拒绝；
- 强类型工具 JSON Schema、未知字段拒绝、身份注入和超时；
- Context/配置 Snapshot、Checkpoint、恢复与可靠工具结果；
- 会话所有权、多轮持久化记忆和 V10 幂等聊天投影；
- HIGH-risk 事务顺序、Outbox Claim fencing、重试及 Excel 解耦；
- Hybrid RAG、RRF、向量不兼容、自愈和诚实降级；
- Testcontainers 下的 MySQL 8 Flyway 全迁移与数据库语义。

执行 Testcontainers 集成测试需要本机 Docker 可用。

### 30 条检索基准

~~~powershell
mvn "-Dtest=HybridRagMvpIntegrationTest#evaluatesThirtyPublicCampusQueriesWithMeasuredMetrics" test
~~~

该基准使用 10 份合成公开校园文档和 30 条查询，验证 `Recall@5`、`MRR@10`、Top-1 来源准确率与平均延迟，并比较 `BM25_ONLY`、`DENSE_ONLY` 和 `HYBRID_RRF`。测试数据不包含学生隐私；确定性评测实现只服务于可重复测试，应用正常运行仍调用本地 Ollama Embedding。

当前确定性回归门槛：

| 模式 | Recall@5 | MRR@10 |
| --- | ---: | ---: |
| `BM25_ONLY` | ≥ 0.65 | ≥ 0.65 |
| `DENSE_ONLY` | ≥ 0.95 | ≥ 0.90 |
| `HYBRID_RRF` | ≥ 0.95 | ≥ 0.85 |

可选的本地真实模型验证：

~~~powershell
mvn -P real-model "-Dtest=HybridRagRealModelDemoTest" test
~~~

该测试需要 Ollama、`mindbridge-qwen2.5-7b` 与 `bge-m3` 已就绪；前置条件不满足时会跳过，不会伪造成功。

### 配置与工作树检查

~~~powershell
docker compose config
git diff --check
~~~

## 安全与运维边界

- 当前 HTTP Basic 认证和内置账号仅供本地演示；生产必须接入机构身份系统、细粒度授权、密钥托管和审计。
- `MCP_EMAIL_MODE=log` 不等于真实告警链路；上线前必须完成 SMTP/HTTP/MCP 通知集成、收件人审核、值班演练和投递监控。
- 共享知识库当前适用于受控的小规模公开校园资料；尚未宣称具备生产级多租户 ACL、撤销、索引代次发布和大规模性能 SLO。
- Hybrid 检索当前在有界切块集合上运行，默认最多 2000 个 chunk；扩大规模前应完成容量与延迟评测。
- Reranker 默认关闭。启用任何本地模型前，应验证输出格式、中文相关性、超时、资源占用和失败回退。
- 多模态信号只作为辅助证据，不能取代人工风险评估或临床流程。
- 任何 HIGH-risk 自动分类都有误报和漏报可能；真实危机响应必须由专业人员和制度流程兜底。

## 进一步阅读

- [单一统一聊天 Runtime 架构决策](docs/adr/ADR-029-single-unified-chat-runtime.md)
- [自主 Agent Runtime 与工具调用说明](docs/agent-runtime-tool-calling.md)
- [P13 架构说明](docs/p13/architecture.md)
- [P13 演示指南](docs/p13/demo-guide.md)
- [P13 最终交付报告](docs/p13/final-delivery-report.md)

---

再次强调：`multimodalAgent` 是工程演示系统，不是医疗器械，不提供诊断结论，也不能单独承担现实世界中的心理危机安全保障。
