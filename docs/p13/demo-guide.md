# P13 Reproducible Demo Guide

## Prerequisites

- Docker Desktop with Compose v2.
- Ollama reachable on `http://127.0.0.1:11434`.
- `mindbridge-qwen2.5-7b-ft:latest` shown by `ollama list`.
- Port 8080 available. MySQL and Redis stay on the private Compose network and do not require host
  ports.

The configuration below is a local demonstration profile. Its sample users and database passwords
are not production credentials.

## Start and health check

```powershell
docker compose config
docker compose up -d --build mysql redis app
docker compose ps
Invoke-RestMethod http://localhost:8080/actuator/health
curl.exe -u student:student123 http://localhost:8080/api/agent/status
```

The default values resolve to:

```text
AGENT_SKILLS_ENABLED=true
RAG_HYBRID_MVP_ENABLED=true
RAG_RETRIEVAL_MODE=HYBRID_RRF
USE_CHROMA=false
AI_PROVIDER=ollama
OLLAMA_MODEL=mindbridge-qwen2.5-7b-ft:latest
MCP_EXCEL_MODE=local
MCP_EMAIL_MODE=log
H2_CONSOLE_ENABLED=false
```

MySQL and Redis health checks gate application startup. Ollama remains host-managed and is reached
through `host.docker.internal`.

## Scenarios

Use `curl.exe -N -u student:student123 -H "Content-Type: application/json" -d '<json>'
http://localhost:8080/api/chat/stream` for each request.

| Scenario | JSON body | Expected evidence |
| --- | --- | --- |
| CHAT | `{"message":"你好，请介绍一下你能做什么"}` | SSE completes; RAG is not called. |
| Sleep | `{"message":"最近总是睡不着，怎样调整作息？"}` | Sleep Skill; CONSULT may inject public-campus evidence. |
| Academic | `{"message":"考试和论文堆在一起，我压力很大"}` | Academic-pressure Skill. |
| Interpersonal | `{"message":"我和室友发生冲突，不知道怎么沟通"}` | Interpersonal Skill. |
| HIGH risk | `{"message":"我不想活了，感觉马上会伤害自己"}` | Durable report/outbox exists before retrieval; no ordinary Skill. |

For memory, copy `sessionId` from the first `meta` SSE event into a second request:

```json
{"sessionId":"<sessionId>","message":"请结合我刚才的情况继续给建议"}
```

For RAG source verification, ingest a unique administrator document and ask a related CONSULT
question. Student/private chat content must never be sent to the admin ingestion endpoint.

```powershell
$body = @{
  source = "demo-sleep.md"
  content = "校园睡眠支持建议保持固定起床时间，睡前减少咖啡因和屏幕刺激。"
} | ConvertTo-Json -Compress
curl.exe -u admin:admin123 -H "Content-Type: application/json" -d $body `
  http://localhost:8080/api/admin/knowledge
```

The bundled documents are intentionally small. Ingest administrator-approved campus material before
expecting topic-specific evidence in a fresh demo database.

## Retrieval result interpretation

- Default demo without `OPENAI_API_KEY`: `HYBRID_RRF` is requested but the result truthfully reports
  `LEXICAL_ONLY_DEGRADED` when lexical evidence exists.
- Offline deterministic suite: exercises real BM25/dense/RRF code with fixture embeddings.
- Real LLM + fixture embedding: proves a real local model can answer from retrieved evidence.
- Real LLM + real embedding: requires a configured external embedding provider and is not part of
  the default demo or offline quality numbers.

Never describe fixture-embedding Recall/MRR as a real-provider result.

## Rollback and optional services

Legacy retrieval rollback:

```powershell
$env:RAG_HYBRID_MVP_ENABLED="false"
docker compose up -d --build app
```

The old Chroma v1 adapter is outside the MVP and disabled by default:

```powershell
docker compose --profile legacy-chroma up -d chroma
$env:USE_CHROMA="true"
docker compose up -d app
```

Mailpit is optional for SMTP demonstrations:

```powershell
docker compose --profile smtp-demo up -d mailpit
```

## Stop

```powershell
docker compose down
```

Do not add `-v` unless deleting local demo database/Redis data is explicitly intended.

## Troubleshooting

- Model errors: verify `Invoke-RestMethod http://127.0.0.1:11434/api/tags` and the exact model name.
- App remains pending: inspect `docker compose ps`; MySQL and Redis must be healthy.
- Hybrid degrades: expected without compatible persisted embeddings; re-ingest after configuring the
  embedding provider/model/dimension.
- Port conflict: stop the service using host port 8080 or override the application port.
