---
name: wow-code-review
description: Use when reviewing Wow framework code, PR diffs, or pre-merge changes involving aggregates, commands, events, sourcing, sagas, projections, command gateway, Query DSL, starter configuration, or Wow tests
---

# Wow Code Review

Review Wow code for framework semantics first, style second. The goal is to catch event-sourcing, CQRS, routing, and test-coverage mistakes before they become production behavior.

## Iron Laws

- No aggregate behavior without focused `AggregateSpec` or verifier coverage.
- No saga branch without trigger and no-command coverage when conditions branch.
- No command handler may mutate aggregate state directly.
- No sourcing handler may perform side effects or non-deterministic work.
- No configuration claim without checking current `@ConfigurationProperties`.
- Keep review requests read-only by default. Do not modify files, post or resolve comments, approve, or merge unless the user explicitly requests that action.
- When approval is explicitly requested, require exact verification evidence.

## Review Flow

1. **Scope the diff**: inspect `git status`, staged and unstaged diffs, untracked files, and the requested PR base or merge-base. List touched modules and files. Resolve module names from `settings.gradle.kts`.
2. **Load context**: use `../wow/SKILL.md` as router. Load only the relevant reference files such as `../wow/references/modeling.md`, `../wow/references/annotations.md`, or `../wow/references/testing.md`.
3. **Find working examples**: use `rg` to compare with nearby aggregate, saga, projection, query, or configuration code.
4. **Review by semantics**: check the lists below before naming style or formatting issues.
5. **Verify tests**: run the narrowest relevant check when feasible and report its exact command and result. If execution is unavailable or out of scope, label the behavior unverified instead of implying it passed.

## Semantic Checklist

| Area | Check |
|------|-------|
| Aggregate | Command handlers return events, enforce invariants, and do not mutate state. |
| State | State changes are deterministic and sourced from events. |
| Events | Event payloads are sufficient to rebuild state and preserve compatibility. |
| API metadata | Commands and domain events include `@Summary` and `@Description` when they are part of the API/domain contract; long descriptions use raw string annotation syntax. |
| Field contracts | Important repeated domain fields are extracted into `<FieldName>Capable` interfaces when reuse improves the model. |
| Routing | Aggregate ID, owner, tenant, and command route rules are explicit and tested. |
| Polymorphic returns | `Any` or multi-event returns declare metadata when current APIs require it. |
| Saga | Trigger, no-command, multi-command, and dependency branches are tested. |
| Projection/EventProcessor | Side effects stay outside aggregates; retry and duplicate-delivery behavior are intentional and tested at the correct runtime boundary. |
| Reactive runtime | No blocking or manual subscription enters core paths; cancellation, concurrency, backpressure, and scheduler boundaries remain intentional. |
| Compatibility | Public API, event serialization/revision, schema, KSP metadata, and generated contracts preserve compatibility unless a breaking change is explicitly authorized. |
| Gradle variants | Dependencies and starter capabilities preserve module boundaries and feature-variant selection. |
| Query DSL | Uses current `condition`, `pagination`, `projection`, and `sort` APIs. |
| Configuration | Examples match current `@ConfigurationProperties` classes. |
| Tests | Kotlin tests use `.assert()`; Java tests follow the current module convention. Cover error paths, lifecycle paths, and branch conditions. |

## Red Flags

- "This aggregate is simple, tests can come later."
- A command handler sets state fields directly.
- Sourcing calls services, clocks, random IDs, databases, or buses.
- Saga has only happy-path tests.
- Projection updates are not idempotent enough for retries.
- Review accepts a config key without checking source properties.
- Verification says "should pass" instead of showing the command.

## Findings Format

Lead with findings, ordered by severity. Use file and line references.

```text
Critical: breaks correctness or event-sourcing guarantees.
Important: likely bug, missing behavior coverage, or framework misuse.
Minor: clarity, maintainability, naming, or follow-up improvement.
```

If there are no findings, say that directly and name residual risks or test gaps.
