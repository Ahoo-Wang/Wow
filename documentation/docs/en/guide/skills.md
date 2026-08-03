---
title: "Agent Skills"
description: "Install four intent-focused Wow Agent Skills for source-backed development, review, debugging, and evidence-gated breaking migrations."
---

# Agent Skills

Wow Agent Skills package framework-specific workflows, architectural invariants, safety boundaries, and completion evidence into four reusable skills. They do not copy the API documentation: annotation parameters, configuration defaults, DSL methods, and generated contracts must be re-established from the current checkout or the pinned target tag. [`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md)

| Entry | Purpose |
|---|---|
| [Wow `skills/`](https://github.com/Ahoo-Wang/Wow/tree/main/skills) | Skill content, references, assets, evals, and source plugin metadata |
| [Ahoo Skills site](https://skills.ahoo.me/) | Plugin catalogue, installation commands, and distribution documentation |
| [Ahoo-Wang/skills](https://github.com/Ahoo-Wang/skills) | Aggregated marketplace for Codex and Claude Code |
| [Agent Skills specification](https://agentskills.io/) | `SKILL.md` format and progressive-disclosure model |

The Wow repository owns the content. Ahoo Skills Hub periodically synchronizes, validates, and generates the `ahoo-wow-skills` plugin. Do not edit generated copies in the aggregation repository. [`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#distribution)

## Four primary skills

The client selects one Primary Skill from the user's **primary outcome**, not from the component names mentioned in the request. That Skill owns the task through completion without switching to another Wow Skill. [`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#selection-order)

| Skill | Use it when | Complete lifecycle |
|---|---|---|
| `wow-develop` | Designing, implementing, testing, refactoring, or explaining Wow behavior/APIs | Read-only: Frame → Discover → Model → Prove facts → Verify → Report; authorized change: Frame → Discover → Model → Prove RED → Change → Verify → Report |
| `wow-review` | Producing findings, readiness evidence, or completing review-and-fix | Scope → Context → Findings → Authorized fix → Post-fix review |
| `wow-debug` | Reproducing and locating an observed failure, or completing diagnose-and-fix | Capture → Reproduce → Locate → Hypothesize → Test → Fix/Conclude |
| `wow-migrate` | Cross-major migration, or storage/data-format cutover from any starting version | Baseline → Target → Matrix, then only explicitly authorized adapt/data/validate/cutover stages |

Source entries: [`wow-develop`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-develop/SKILL.md#develop-wow-applications), [`wow-review`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-review/SKILL.md#review-wow-changes), [`wow-debug`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-debug/SKILL.md#debug-wow-failures), and [`wow-migrate`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-migrate/SKILL.md#migrate-wow-across-breaking-boundaries).

```mermaid
flowchart TD
    Task["User task"] --> Intent{"Primary outcome"}
    Intent -->|Design, implement, test, explain| Develop["wow-develop"]
    Intent -->|Findings or merge readiness| Review["wow-review"]
    Intent -->|Reproducer or root cause| Debug["wow-debug"]
    Intent -->|Cross-major or data/storage cutover| Migrate["wow-migrate"]
    Intent -->|No Wow behavior involved| None["Do not activate"]

    classDef default fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Intent fill:#161b22,stroke:#30363d,color:#e6edf3
    linkStyle default stroke:#8b949e
```

### Selection order

1. If v6→v8 compatibility, or storage/data cutover and rollback from any version, is primary, use `wow-migrate`.
2. If there is an observed failure, hang, incorrect state, or reproducer and the goal is root cause, use `wow-debug`.
3. If the goal is findings, approval evidence, or merge readiness, use `wow-review`.
4. If the goal is designing, changing, testing, or explaining Wow, use `wow-develop`.
5. Do not activate for unrelated Kotlin/Gradle, dashboard, or documentation-only work.

Keep review-and-fix inside `wow-review` and diagnose-and-fix inside `wow-debug`. This preserves authorization, evidence, and diff baselines throughout the task.

## Progressive loading

Each `SKILL.md` contains only the core procedure and selection rules. Domain material loads on demand from the [development reference table](https://github.com/Ahoo-Wang/Wow/blob/main/skills/wow-develop/SKILL.md#load-one-domain-reference-first).

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

References contain stable decisions, source-discovery methods, and verification boundaries. Discover complete annotation parameters, test DSL APIs, configuration keys, defaults, and backend lists from the target version. [`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#content-model)

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
python3 -m unittest scripts/test_validate_wow_skills.py scripts/test_run_wow_skill_evals.py
```

The two entry scripts remain stable, thin CLI façades and support both direct-script and `python -m scripts...` execution. The validator separates core constraints, trace schema, plugin package, eval contracts, and repository orchestration; the runner separates model, I/O, Git, security, state, preparation, evidence, oracles, assertions, verification, and cleanup. New rules belong in the responsibility-specific module under `scripts/wow_skill_validator/` or `scripts/wow_skill_runner/`; entry scripts only parse arguments and orchestrate commands.

The repository-owned validator is self-contained. It validates standard Skill metadata, `openai.yaml`, the explicit plugin list, contained resource paths, shell syntax without executing `--help`, and activation/behavior contracts for all four Skills. [`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#validation)

Structural validation cannot prove an eval is executable, automatically activated, or behaviorally correct. `scripts/run_wow_skill_evals.py` freezes the contract and a runtime-only plugin copy without `evals/`, prepares a pinned isolated fixture, and compares a full runner-owned path manifest that records directories and rejects special files. During `prepare`, a protected key domain-signs the RUN v2 descriptor, including the pinned adapter identity and the case, repository, workspace, revision, contract, plugin, and baseline. HMAC evidence independently signs `requestSha256`, which is the SHA-256 of the exact UTF-8 bytes stored in `request.json`, not a canonical-JSON hash. Verification uses only that frozen snapshot and does not reread the live skills package, so an editable `run.json` cannot rebase or redirect a result and asynchronous runs survive later source updates. The standalone review clone receives the exact subject/base object closure in a runner-owned pack before its remote is removed, so detached commits do not depend on advertised refs or external objects. The runner accepts v2 traces only from a pinned adapter with protected HMAC attestation, enforced workspace policy, and commands bound to root cwd plus the resolved executable; activation runs must stop immediately after routing. Authenticated cleanup re-seals its completed lifecycle state; explicit recovery of a damaged marker requires the protected key, uses fixed runner-owned paths plus a caller-supplied source repository, and writes a key-signed idempotency tombstone. Recovery does not cover simultaneous loss of the trust key. Runner-owned oracles independently cover Cart capacity branches, an exact platform contract, and synthetic data interruption/resume, idempotency, checksum, and reconciliation; the interrupted data gate seals an unpredictable, JSON-insignificant byte prefix to reject restart-from-zero rewrites. The hidden Cart test reuses only the workspace-local Gradle cache populated by the signed RED/GREEN commands, forces `--offline` task re-execution, and returns `UNSUPPORTED` rather than `FAIL` when the wrapper, dependency cache, or Java toolchain is unavailable. Missing trust, activation/tool traces, or enforcement also return `UNSUPPORTED`; malformed or tampered evidence returns `ERROR`; assertion failures return `FAIL` and a nonzero CLI exit. See the [runner contract](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md#validation) for `prepare`, `verify`, and `cleanup` commands.

## Related pages

| Page | Relationship |
|---|---|
| [Getting Started](./getting-started.md) | Build a runnable Wow application |
| [Aggregate Modeling](./modeling.md) | Aggregate background for `wow-develop` |
| [Test Suite](./test-suite.md) | Current Aggregate/Saga testing APIs |
| [Troubleshooting](./troubleshooting.md) | Runtime and configuration context for `wow-debug` |
| [Migrate Wow v6 to v8](./migration/v6-to-v8.md) | Framework migration topic for `wow-migrate` |
