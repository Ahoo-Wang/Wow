---
name: "wow-develop"
description: "Design, implement, test, refactor, or explain Wow behavior in downstream applications. Never activate for development of the Wow framework repository itself. Require scoped me.ahoo.wow imports, wow-* dependencies, or an explicit request to use, adopt, configure, or explain Wow in a downstream application; checkout-wide markers and generic DDD/CQRS terms are insufficient. Use for first-time adoption and routine same-major application upgrades. Do not use for unrelated code, diff review, diagnosis, release/build tooling, or breaking migration/data cutover."
---

# Develop Wow Applications

## Scope gate

Before doing development work, confirm that the target is a downstream application rather than the Wow framework repository itself. Then require scoped source to contain `me.ahoo.wow` imports or `wow-*` dependencies, or the task to explicitly request using, adopting, configuring, or explaining Wow in that downstream application. Framework-repository modules, markers elsewhere in a checkout, negated/comparative mentions, and generic Kotlin, Java, Spring, Reactor, DDD, CQRS, or Event Sourcing terms do not qualify. If no downstream scoped Wow evidence exists, state that this Skill does not apply and stop using it.

Own the complete development task. Do not route to another Wow Skill.

## Contract

- Treat the current checkout, its tests, generated contracts, and resolved dependencies as authoritative.
- Treat commands as intent, domain events as committed facts, and sourced state as reconstructed memory.
- Keep aggregate invariants inside the aggregate boundary and external side effects outside it.
- Preserve reactive execution, serialization compatibility, module boundaries, and public contracts unless the user authorizes a breaking change.
- Keep explanation, lookup, design-only, and other read-only requests non-mutating. Enter test-first implementation only when the user requests a code or document change.
- Use RED→GREEN→REFACTOR for behavior changes. If a change is not testable at the unit level, name the narrowest replacement evidence before editing.
- Report source-backed answers or changed files and behavior, exact verification commands and results, compatibility or operational risk, and remaining evidence gaps. Never replace an unavailable test with “should pass.”

## Load one domain reference first

| Primary scope | Load |
|---|---|
| Aggregate, command, event, sourcing, lifecycle, tenant/owner routing | `references/aggregate-sourcing.md` |
| Saga, Projection, EventProcessor, retry, idempotency | `references/saga-processors.md` |
| CommandGateway, wait, delivery ambiguity, HTTP command routes | `references/command-delivery.md` |
| Query DSL, read-model filtering, pagination, projection, sort | `references/query-read-model.md` |
| Spring Boot starter, feature capability, storage or bus routing | `references/starter-storage.md` |
| Runtime ownership, readiness, fatal handling, drain or shutdown | `references/runtime-lifecycle.md` |
| Uniqueness, reservation, rollback, or reprepare with PrepareKey | `references/prepare-key.md` |

Load a second reference only when the task genuinely crosses domains. Read only the relevant sections, then verify every exact symbol and default in the current source.

## Source discovery

For an annotation, configuration property, DSL method, gateway API, or generated contract:

1. Find its definition.
2. Find every compiler/runtime consumer.
3. Find representative tests.
4. Compare the target module's usage.
5. Update generated outputs only through their source or generator.

## Shared gates

- Do not introduce blocking or manual subscription into reactive runtime paths.
- Verify event/schema/API compatibility when changing public messages or metadata.
- Use the assertion style already established by the target module; Kotlin Wow tests normally use `me.ahoo.test.asserts.assert` and `.assert()`.
