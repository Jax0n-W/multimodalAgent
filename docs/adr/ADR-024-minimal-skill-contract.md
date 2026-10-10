# ADR-024: Minimal Deterministic Skill Contract

- Status: Accepted
- Date: 2026-10-10
- Phase: P11.3
- Builds on: ADR-022 and ADR-023

## Context and scope

Fresh Agent runs need a small set of trusted, reusable behavioral instructions without adding an
LLM router, another execution engine, or mutable plugin infrastructure. A selected Skill must be
deterministic, auditable in the context snapshot, unable to grant tool authority, and irrelevant
to recovery after admission.

P11.3 provides only immutable definitions, a static registry, deterministic resolution, and a
`ContextSource`. Dynamic installation, marketplaces, multi-Skill planning, workflows, generated
Skills, and external instruction files are outside this phase.

## Skill definition, registry, and resolver

`SkillDefinition` is immutable and contains a stable ID, version, description, bounded trusted
instructions, sorted required-tool names, sorted match keywords, and explicit priority. IDs,
versions, text, collections, keywords, and instruction length are validated. Definitions contain
no user or session data and are created only from server-side code.

`SkillRegistry` takes a defensive copy, sorts definitions by ID then version, rejects duplicate
identities, and exposes only immutable ordered results. It has no database, Redis, remote service,
or runtime mutation path. P11.3 includes `sleep-guidance@1`, whose instructions provide general,
non-diagnostic sleep-hygiene support without changing existing risk escalation or referral rules.

`DeterministicSkillResolver` inspects only USER messages from the current request. Known keywords
are matched case-insensitively without a model call. Matches are ordered by priority descending,
then Skill ID and version ascending, and at most one Skill is selected. User text can trigger a
trusted definition but cannot create or modify its identity or instructions.

## ContextSource integration and snapshot provenance

`SkillContextSource` has stable identity `agent-skills@1` and order zero. ADR-022 sorting therefore
produces this source order without relying on registration order:

```text
agent-skills
conversation-memory
request-messages
```

A selected Skill contributes one SYSTEM message containing its ID, version, and exact trusted
instructions. No match contributes an empty source. The normal assembler records contribution
provenance and content hash and includes the message in the immutable V1 canonical context
snapshot. Version or instruction changes therefore change semantic snapshot identity without
changing the V1 JSON schema. Skill prompts are never appended in `AgentRunner` or transported via
runtime attributes.

## Tool governance boundary

`requiredTools` expresses a capability prerequisite, not authorization. `ContextAssemblyInput`
receives an immutable copy of `AgentRunSpec.allowedTools` from the fresh-execution coordinator.
After resolution and before contribution, the source requires:

```text
selectedSkill.requiredTools subset-of currentRun.allowedTools
```

Failure aborts context assembly before snapshot persistence and P6 admission. The Skill source
cannot mutate `allowedTools` or `approvedToolCallIds`, invoke `ToolExecutor`, or bypass
`DefaultToolPolicyEngine`. Model-generated calls still pass through the existing registry,
validation, policy, middleware, budget, and durable outcome path. Merely mentioning a tool in an
instruction grants no permission.

## Recovery and memory consistency

Skills are resolved only during fresh context assembly. Recovery remains structurally independent
of the Skill package and continues from persisted `RecoveryCheckpoint.messages`; it neither reads
the current registry nor assembles context again. A later Skill version changes future fresh runs
only and cannot alter an existing checkpoint.

ADR-023 memory reconstruction continues to extract only the exact `request-messages@1` provenance
segment and the durable final answer. Historical Skill SYSTEM messages and complete historical
snapshots are not copied into subsequent conversation turns.

## Failure semantics

Invalid definitions and duplicate identities fail during registry construction. Resolver failure,
null resolution, or missing required-tool authority fails closed at the existing ContextSource
boundary. These failures produce no context snapshot, AgentRun admission, model call, or tool call.
No-match is a valid empty contribution and preserves the request messages.

## Configuration and compatibility

`multimodal-agent.runtime.skills.enabled` defaults to `false`. Disabled production assembly has
the same sources and model-visible messages as P11.2. Enabling it statically installs the trusted
built-in catalog. Legacy Chat endpoints and their memory behavior are unchanged.

## Deferred work

Dynamic installation or hot reload, external instruction files, Skill APIs or persistence,
multiple-Skill planning, dependencies, LLM routing, Skill generation, P11.4 Chat migration, P12
RAG, long-term user profiles, and broader production controls are explicitly deferred.
