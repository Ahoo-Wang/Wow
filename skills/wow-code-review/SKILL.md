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
- No approval without exact verification evidence.

## Review Flow

1. **Scope the diff**: list touched modules and files. Resolve module names from `settings.gradle.kts`.
2. **Load context**: use `../wow/SKILL.md` as router. Load only the relevant reference files such as `../wow/references/modeling.md`, `../wow/references/annotations.md`, or `../wow/references/testing.md`.
3. **Find working examples**: use `rg` to compare with nearby aggregate, saga, projection, query, or configuration code.
4. **Review by semantics**: check the lists below before naming style or formatting issues.
5. **Verify tests**: identify the narrowest Gradle command that proves the changed behavior.

## Semantic Checklist

| Area | Check |
|------|-------|
| Aggregate | Command handlers return events, enforce invariants, and do not mutate state. |
| State | State changes are deterministic and sourced from events. |
| Events | Event payloads are sufficient to rebuild state and preserve compatibility. |
| API metadata | Commands and domain events include `@Summary` and `@Description`; long descriptions use raw string annotation syntax. |
| Field contracts | Important repeated domain fields are extracted into `<FieldName>Capable` interfaces when reuse improves the model. |
| Routing | Aggregate ID, owner, tenant, and command route rules are explicit and tested. |
| Polymorphic returns | `Any` or multi-event returns declare metadata when current APIs require it. |
| Saga | Trigger, no-command, multi-command, and dependency branches are tested. |
| Projection/EventProcessor | Side effects stay outside aggregates; retry behavior is intentional and annotation-driven. |
| Query DSL | Uses current `condition`, `pagination`, `projection`, and `sort` APIs. |
| Configuration | Examples match current `@ConfigurationProperties` classes. |
| Tests | Use `.assert()`, cover error paths, lifecycle paths, and branch conditions. |

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
