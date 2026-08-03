---
title: "Agent Skills"
description: "Install four intent-focused Wow Agent Skills for source-backed development, review, debugging, and v6-to-v8 migration."
---

# Agent Skills

Wow Agent Skills package framework-specific workflows, architectural invariants, safety boundaries, and completion evidence into four reusable skills. They do not copy the API documentation: annotation parameters, configuration defaults, DSL methods, and generated contracts must be re-established from the current checkout or the pinned target tag. [`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#L1-L5)

| Entry | Purpose |
|---|---|
| [Wow `skills/`](https://github.com/Ahoo-Wang/Wow/tree/main/skills) | Skill content, references, assets, evals, and source plugin metadata |
| [Ahoo Skills site](https://skills.ahoo.me/) | Plugin catalogue, installation commands, and distribution documentation |
| [Ahoo-Wang/skills](https://github.com/Ahoo-Wang/skills) | Aggregated marketplace for Codex and Claude Code |
| [Agent Skills specification](https://agentskills.io/) | `SKILL.md` format and progressive-disclosure model |

The Wow repository owns the content. Ahoo Skills Hub periodically synchronizes, validates, and generates the `ahoo-wow-skills` plugin. Do not edit generated copies in the aggregation repository. [`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#L54-L56)

## Four primary skills

The client selects one Primary Skill from the user's **primary outcome**, not from the component names mentioned in the request. That Skill owns the task through completion without switching to another Wow Skill. [`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#L7-L26)

| Skill | Use it when | Complete lifecycle |
|---|---|---|
| `wow-develop` | Designing, implementing, testing, refactoring, or explaining Wow behavior/APIs | Frame → Discover → Model → Prove → Change → Verify → Report |
| `wow-review` | Producing findings, readiness evidence, or completing review-and-fix | Scope → Context → Findings → Authorized fix → Post-fix review |
| `wow-debug` | Reproducing and locating an observed failure, or completing diagnose-and-fix | Capture → Reproduce → Locate → Hypothesize → Test → Fix/Conclude |
| `wow-migrate` | Moving an existing Wow v6 application to a pinned Wow v8 release | Baseline → Target → Matrix → Adapt → Rehearse → Cut over → Roll back |

Source entries: [`wow-develop`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-develop/SKILL.md#L1-L27), [`wow-review`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-review/SKILL.md#L1-L38), [`wow-debug`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-debug/SKILL.md#L1-L39), and [`wow-migrate`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-migrate/SKILL.md#L1-L47).

```mermaid
flowchart TD
    Task["User task"] --> Intent{"Primary outcome"}
    Intent -->|Design, implement, test, explain| Develop["wow-develop"]
    Intent -->|Findings or merge readiness| Review["wow-review"]
    Intent -->|Reproducer or root cause| Debug["wow-debug"]
    Intent -->|Existing v6 app to v8| Migrate["wow-migrate"]
    Intent -->|No Wow behavior involved| None["Do not activate"]

    classDef default fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Intent fill:#161b22,stroke:#30363d,color:#e6edf3
    linkStyle default stroke:#8b949e
```

### Selection order

1. If v6→v8 compatibility, data, or cutover is primary, use `wow-migrate`.
2. If there is an observed failure, hang, incorrect state, or reproducer and the goal is root cause, use `wow-debug`.
3. If the goal is findings, approval evidence, or merge readiness, use `wow-review`.
4. If the goal is designing, changing, testing, or explaining Wow, use `wow-develop`.
5. Do not activate for unrelated Kotlin/Gradle, dashboard, or documentation-only work.

Keep review-and-fix inside `wow-review` and diagnose-and-fix inside `wow-debug`. This preserves authorization, evidence, and diff baselines throughout the task.

## Progressive loading

Each `SKILL.md` contains only the core procedure and selection rules. Domain material loads on demand from the [development reference table](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-develop/SKILL.md#L29-L41).

| Task | First reference |
|---|---|
| Aggregate, command, event, sourcing | `aggregate-sourcing.md` |
| Saga, Projection, EventProcessor | `saga-processors.md` |
| CommandGateway, waits, HTTP command routes | `command-delivery.md` |
| Query DSL and read models | `query-read-model.md` |
| Starter, storage, and buses | `starter-storage.md` |
| Runtime lifecycle | `runtime-lifecycle.md` |
| PrepareKey uniqueness and reservation | `prepare-key.md` |
| Test level and completion evidence | `verification-evidence.md` |

References contain stable decisions, source-discovery methods, and verification boundaries. Discover complete annotation parameters, test DSL APIs, configuration keys, defaults, and backend lists from the target version. [`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#L28-L36)

## Installation

### Codex

```bash
codex plugin marketplace add Ahoo-Wang/skills --ref main
codex plugin add ahoo-wow-skills@ahoo-skills
```

### Claude Code

```text
/plugin marketplace add https://github.com/Ahoo-Wang/skills
/plugin install ahoo-wow-skills
```

Treat the [Ahoo Skills site](https://skills.ahoo.me/) as the source for current installation commands and publication state. After an update, use the client's refresh mechanism or a new task to confirm that all four Skills are discoverable.

This four-Skill architecture is intentionally breaking: legacy names and compatibility aliases are not distributed. Existing installations must refresh or reinstall the plugin after publication.

## Usage

Provide at least the objective, scope, authorization mode, and completion evidence:

| Information | Example |
|---|---|
| Objective | "Add cancellation behavior and tests to `Order`" |
| Scope | "Only change `example-domain`; preserve public API compatibility" |
| Mode | "Review only; do not edit" or "Diagnose and fix" |
| Verification | "Run `:example-domain:test` and report the exact result" |

```mermaid
sequenceDiagram
    autonumber
    participant User
    participant Client as Agent client
    participant Skill as Primary Skill
    participant Repo as Current checkout
    participant Gate as Verification gate

    User->>Client: Objective, scope, authorization, completion criteria
    Client->>Skill: Activate one Primary Skill
    Skill->>Repo: Read definitions, consumers, tests, and current diff
    Repo-->>Skill: Return the target version's actual contract
    Skill->>Gate: Run the narrowest test/check
    Gate-->>Skill: Return results and evidence gaps
    Skill-->>User: Deliver the change or evidence-backed conclusion
```

## Validation and maintenance

Maintainers run:

```bash
python3 scripts/validate_wow_skills.py
```

The command validates standard Skill structure, `openai.yaml`, the explicit plugin list, resource links, the migration script, and eval schemas for all four Skills. [`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#L38-L52)

Structural validation cannot prove automatic activation. Run every `evals/activation.jsonl` prompt in a fresh task and inspect the real activation trace. Use `evals/behavior.jsonl` to verify source-first execution, read-only authorization, test evidence, and migration safety. An answer that merely looks correct is not proof of routing or behavior.

## Related pages

| Page | Relationship |
|---|---|
| [Getting Started](./getting-started.md) | Build a runnable Wow application |
| [Aggregate Modeling](./modeling.md) | Aggregate background for `wow-develop` |
| [Test Suite](./test-suite.md) | Current Aggregate/Saga testing APIs |
| [Troubleshooting](./troubleshooting.md) | Runtime and configuration context for `wow-debug` |
| [Migrate Wow v6 to v8](./migration/v6-to-v8.md) | Framework migration topic for `wow-migrate` |
