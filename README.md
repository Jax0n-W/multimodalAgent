# multimodalAgent Agent

multimodalAgent 是一个校园心理健康智能体

- 动态路由 RAG：先识别 `CHAT / CONSULT / RISK`，闲聊不查知识库，咨询与风险消息才进入检索增强。
- SSE 流式输出：`/api/chat/stream` 返回 `text/event-stream`，适合前端做打字机效果。
- 后台心理状态识别：记录情绪标签、情绪分数、风险等级和置信度，但学生端不展示评估结果。
- 数据闭环：咨询/风险消息写入数据库，高风险先写 Excel，再触发邮件或 HTTP MCP 预警。
- Spring AI 模型接入：默认通过 `ollama` 调用项目模型，也可切到 `openai`；`mock` 只作为无模型离线演示。
- 可替换知识库：默认本地轻量检索，可打开 Chroma 镜像和查询。

大模型 LoRA 微调、合并、GGUF 转换和 Ollama 接入流程见：[docs/qwen25-7b-lora-finetune-guide.md](docs/qwen25-7b-lora-finetune-guide.md)。

## 目录

```text
src/main/java/com/multimodalAgent/agent
├── config                 # 配置、安全、AI/MCP Bean
├── controller             # Chat / Knowledge / Report API
├── domain                 # JPA 实体与枚举
├── dto                    # 请求与响应对象
├── repository             # Spring Data JPA
├── security               # 当前用户与认证查询
└── service
    ├── ai                 # Spring AI 模型适配器、mock 客户端与 Prompt
    ├── knowledge          # 切块、检索、Chroma 网关
    └── mcp                # Excel 与邮件/HTTP 预警工具
```

## 快速启动

当前目录已经本地安装好 JDK 17 和 Maven，Ollama 也可以通过脚本自动启动。最省事的方式是直接运行：

```bash
cd multimodalAgent
./scripts/run-dev.sh
```

启动后打开：

```text
http://localhost:8080
```

如果想手动分两步启动，先在一个终端启动 Ollama：

```bash
cd multimodalAgent
./scripts/start-ollama.sh
```

再在另一个终端运行项目：

```bash
cd multimodalAgent
JAVA_HOME="$PWD/.tools/amazon-corretto-17.jdk/Contents/Home" \
  .tools/apache-maven-3.9.9/bin/mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

也可以运行已经打好的 jar：

```bash
cd multimodalAgent
JAVA_HOME="$PWD/.tools/amazon-corretto-17.jdk/Contents/Home" \
  .tools/amazon-corretto-17.jdk/Contents/Home/bin/java \
  -jar target/multimodalAgent-agent-0.1.0.jar --server.address=127.0.0.1 --server.port=8080
```

默认使用 H2 文件数据库、Ollama 大模型、本地 Excel 文件和日志预警。页面左上角会显示当前模型模式；如果本机没有启动 Ollama，聊天接口会提示模型连接失败。首次启动会创建两个账号：

```text
admin / admin123
student / student123
```

## 调用示例

```bash
curl -N -u student:student123 \
  -H 'Content-Type: application/json' \
  -d '{"message":"我最近很焦虑，晚上总是睡不着"}' \
  http://localhost:8080/api/chat/stream
```

高风险示例会触发报告、Excel 写入和预警：

```bash
curl -N -u student:student123 \
  -H 'Content-Type: application/json' \
  -d '{"message":"我不想活了，感觉撑不下去了"}' \
  http://localhost:8080/api/chat/stream
```

管理员查看后台报告：

```bash
curl -u admin:admin123 http://localhost:8080/api/admin/reports
```

查看当前是否接入真实大模型：

```bash
curl -u student:student123 http://localhost:8080/api/agent/status
```

管理员追加知识库：

```bash
curl -u admin:admin123 \
  -H 'Content-Type: application/json' \
  -d '{"source":"sleep-guide","content":"失眠时可先固定起床时间，减少睡前屏幕刺激，必要时联系校心理中心。"}' \
  http://localhost:8080/api/admin/knowledge
```

## 接入 Ollama / LoRA 模型

默认模型配置就是本地 Ollama 路线，模型名为：

```text
multimodalAgent-qwen2.5-7b-ft:latest
```

本地模型由这个 GGUF 权重创建：

```text
models/multimodalAgent-qwen2.5-7b-ft/multimodalAgent-qwen2.5-7b-ft-q4_k_m.gguf
```

首次运行或重新导入模型时执行：

```bash
cd multimodalAgent
./scripts/create-finetuned-model.sh
```

之后直接启动项目：

```bash
cd multimodalAgent
./scripts/run-dev.sh
```

如果终端提示 `ollama: command not found`，说明只是命令链接没建好；本项目脚本会直接调用 `/Applications/Ollama.app/Contents/Resources/ollama`。

没有本地模型、只想离线演示完整业务流程时，才使用 mock：

```bash
cd multimodalAgent
AI_PROVIDER=mock \
JAVA_HOME="$PWD/.tools/amazon-corretto-17.jdk/Contents/Home" \
  .tools/apache-maven-3.9.9/bin/mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

也可以不用脚本，手动指定本地模型启动：

```bash
cd multimodalAgent
AI_PROVIDER=ollama \
OLLAMA_BASE_URL=http://localhost:11434 \
OLLAMA_MODEL=multimodalAgent-qwen2.5-7b-ft:latest \
JAVA_HOME="$PWD/.tools/amazon-corretto-17.jdk/Contents/Home" \
  .tools/apache-maven-3.9.9/bin/mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

## 打包给别人运行

模型文件较大，建议单独压缩发送：

```text
models/multimodalAgent-qwen2.5-7b-ft/multimodalAgent-qwen2.5-7b-ft-q4_k_m.gguf
```

生成不含模型权重的应用发布包：

```bash
cd multimodalAgent
./scripts/package-release.sh
```

脚本会在 `dist/` 下生成 `multimodalAgent-app-时间戳.tar.gz`。发布包包含源码、Dockerfile、docker-compose、脚本、文档、`models/multimodalAgent-qwen2.5-7b-ft/Modelfile` 和 `data/lora/psychqa.jsonl` 数据集；会排除模型权重、模型 zip、运行数据库、Excel 输出、日志、PDF 文档、`target/`、`.m2/`、`.tools/`、IDE 配置等本机产物。

收到项目的人需要把模型 zip 解压到：

```text
multimodalAgent/models/multimodalAgent-qwen2.5-7b-ft/
```

然后执行：

```bash
cd multimodalAgent
./scripts/create-finetuned-model.sh
./scripts/run-dev.sh
```

如果用 Docker 部署数据库、Redis、Chroma、Mailpit：

```bash
docker compose up -d mysql redis chroma mailpit
./scripts/create-finetuned-model.sh
./scripts/run-dev.sh
```

如果不是 macOS，或 Ollama/JDK/Maven 不在默认路径，需要先安装 Ollama、JDK 17、Maven，并按实际路径设置 `OLLAMA_BIN`、`JAVA_HOME`、`MAVEN_BIN`。

## 接入 OpenAI

```bash
cd multimodalAgent
AI_PROVIDER=openai \
OPENAI_API_KEY=你的_API_Key \
OPENAI_MODEL=gpt-4o-mini \
JAVA_HOME="$PWD/.tools/amazon-corretto-17.jdk/Contents/Home" \
  .tools/apache-maven-3.9.9/bin/mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

## 使用 MySQL、Chroma、SMTP

启动依赖：

```bash
docker compose up -d mysql redis chroma mailpit
```

使用 MySQL profile：

```bash
AI_PROVIDER=ollama \
USE_CHROMA=true \
MCP_EMAIL_MODE=smtp \
ALERT_MAIL_RECIPIENTS=counselor@example.com \
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

Mailpit 管理页面：`http://localhost:8025`

## MCP 工具模式

Excel 工具：

- `MCP_EXCEL_MODE=local`：默认写入 `./data/multimodalAgent-reports.xlsx`
- `MCP_EXCEL_MODE=http`：调用 `MCP_EXCEL_URL/write`

邮件工具：

- `MCP_EMAIL_MODE=log`：默认只记录日志，便于本地演示
- `MCP_EMAIL_MODE=smtp`：使用 Spring Mail 发送
- `MCP_EMAIL_MODE=http`：调用 `MCP_EMAIL_URL/send`

高风险链路按文档实现为：写入报告 -> 写入 Excel -> Excel 成功后发送预警 -> 更新状态。
