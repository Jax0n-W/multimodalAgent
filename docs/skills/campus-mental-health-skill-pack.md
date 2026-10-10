# Campus Mental Health Skill Pack

## Scope

P11.3B extends ADR-024's static, deterministic Skill catalog with four campus mental-health
support strategies. It does not add diagnosis, clinical assessment, a workflow engine, dynamic
loading, persistence, an LLM router, or tool authority. At most one Skill is selected from the
current request, and no match remains valid.

## Catalog

| Skill | Priority | Business focus | Representative triggers |
|---|---:|---|---|
| `academic-stress@1` | 400 | Exams, papers, graduation, grades, burnout, accumulated work | 考试焦虑、考试紧张、学业压力、论文压力、成绩焦虑 |
| `interpersonal-support@1` | 300 | Roommates, friends, classmates, communication and boundaries | 室友矛盾、朋友吵架、朋友不理我、人际冲突、沟通困难 |
| `sleep-guidance@2` | 200 | Sleep onset, quality, waking and schedule disruption | 失眠、睡不着、入睡困难、睡眠质量差、作息紊乱 |
| `emotion-support@1` | 100 | Low mood, loneliness, sadness, grievance and irritability | 心情低落、很难过、感到孤独、情绪不好、很委屈 |

All four definitions use `requiredTools = Set.of()`. `sleep-guidance@1` is deliberately absent
from the active registry. Historical V1 snapshots and recovery checkpoints remain self-contained
and unchanged; fresh runs select only V2.

## Instruction design

Each trusted instruction contains the same internal sections: role, applicable scenarios, support
goal, suggested interaction flow, prohibited behavior and professional boundary, answer style,
and referral or escalation conditions. These sections guide model behavior but are not a response
template to repeat to a student.

The instructions emphasize listening, student choice, a small number of practical low-risk
options, and referral when difficulties persist or substantially affect daily functioning. They
prohibit medical diagnosis, medication advice, guaranteed outcomes, invented campus contact
details, claims of professional licensure, unnecessary collection of private information, and
claims that an alarm or notification occurred when it did not.

## Routing and priority

`DeterministicSkillResolver` continues to inspect only USER messages in the current request and
uses case-insensitive substring matching. Keywords are intentionally phrases rather than broad
single words such as “压力”, “关系”, or “不好”. Matches retain ADR-024 ordering: priority
descending, then Skill ID and version. This produces stable decisions for supported multi-topic
cases:

- exam stress causing sleeplessness selects `academic-stress@1`;
- low mood after a roommate conflict selects `interpersonal-support@1`;
- simultaneous explicit study pressure and poor sleep selects `academic-stress@1`.

Priority is a deterministic product rule, not semantic understanding. The system does not claim
that it can identify the primary concern in every complex sentence. Unsupported or ambiguous
language may remain unmatched or select the most explicit configured phrase.

Two narrow false-positive controls are applied. An occurrence preceded by an explicit negation
such as “没有”, “并不”, or “不是” is ignored. Definition-style questions such as “失眠是什么
意思” or “请解释考试焦虑这个概念” are also ignored. This is not general Chinese parsing and
does not attempt to infer implicit negation, irony, or context-dependent meaning.

## High-risk and professional boundary

Before ordinary Skill matching, the resolver reuses the existing explicit `RiskLexicon`
high-risk signal rule and suppresses Skill selection. Suppression means only that a normal support
Skill is not loaded; it is not risk assessment, crisis response, notification, referral, or proof
of safety. Those responsibilities remain in the authenticated chat business path.

The Skill layer does not lower a risk level, authorize a tool, send a report, contact staff, or
claim that a person has been notified. Limited keyword coverage is not a clinical safety guarantee.

## Dataset and evaluation

`src/test/resources/campus-skill-routing-cases.tsv` contains 51 independent cases:

| Category | Cases |
|---|---:|
| Sleep support | 8 |
| Academic stress | 9 |
| General emotion support | 8 |
| Interpersonal support | 8 |
| Multi-topic conflicts | 6 |
| Negation, knowledge questions, and no-match | 6 |
| High-risk mixed expressions | 6 |

Every row records input, expected Skill or no Skill, expected behavioral intent, and whether an
independent safety route is required. The automated test repeats each decision to verify
determinism, reports ordinary routing accuracy separately with a 90% minimum, and never folds the
six high-risk cases into that score. The dataset measures only these routing rules; it does not
measure treatment effectiveness or establish clinical safety.

## Snapshot, memory, tools, and recovery

A selected definition remains a versioned SYSTEM message from `agent-skills@1`, with its exact
content included in immutable context snapshot provenance. Conversation memory remains a separate
source, and historical Skill SYSTEM messages are not reconstructed as conversation turns. The
resolver does not read session history to make a sticky selection.

Empty `requiredTools` does not expand `allowedTools`; every model-generated call still passes the
existing tool governance chain. Recovery resumes checkpoint messages and never invokes the
current registry or resolver, so catalog upgrades cannot rewrite an admitted run.

## Manual real-model acceptance checklist

With Runtime and Skills enabled against an explicitly configured model, verify the following
without treating generated text as proof of psychological effectiveness:

1. “最近总是睡不着” gives empathetic, concise, non-diagnostic sleep support.
2. “明天考试，我特别紧张” acknowledges pressure and offers optional realistic next steps.
3. “最近莫名其妙地很难过” listens before advising and does not force positivity.
4. “我和室友一直有矛盾” distinguishes facts from assumptions and avoids declaring a winner.
5. A second turn receives durable conversation context while Skill selection still depends only
   on that turn's current request.
6. A high-risk expression enters the independent safety path and is not presented as handled by a
   normal Skill.

Reproducible setup uses the project's normal AI provider configuration, sets
`AGENT_RUNTIME_ENABLED=true` and `AGENT_SKILLS_ENABLED=true`, starts the application, and submits
the checklist prompts through the authenticated Runtime entry point. Ollama is not started by the
automated test suite.

## Known limitations

- Matching is phrase-based and cannot reliably interpret paraphrase, sarcasm, mixed intent, or
  every form of negation.
- Fixed priority resolves only configured conflicts and is not a claim of semantic topic ranking.
- The catalog supplies response behavior, not clinical diagnosis or a complete crisis service.
- Campus contact details are intentionally not embedded because deployment-specific verified
  information is unavailable.
- Real-model quality varies with the configured provider and requires human review.

## P11.4 integration requirements

The student chat migration must run authenticated risk classification and multimodal fusion before
final-answer generation, preserve reporting and human-alert business paths independently of model
success, and place trusted business safety context in the immutable Runtime snapshot. A normal
Skill must never override HIGH risk. Chat session identity must remain user-bound, Runtime memory
must not be combined with Redis legacy prompt history, and recovery must not repeat classification,
Skill resolution, reports, or notifications. Legacy-compatible SSE must expose only the final safe
answer and emit success only after durable completion.
