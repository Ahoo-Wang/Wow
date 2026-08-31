---
name: "wow-develop"
description: "Develop or explain Wow behavior in downstream apps, including first adoption and routine same-major non-breaking upgrades. Activate only for scoped me.ahoo.wow imports/wow-* dependencies or an explicit downstream Wow request; generic DDD/CQRS or checkout-wide markers do not qualify. Exclude the Wow framework repository, unrelated work, review, diagnosis, release/tooling, and breaking migration/data cutover."
---

# Develop Wow Applications

## Scope gate

Use only for a downstream application; the Wow framework repository and its modules never qualify. Require scoped `me.ahoo.wow` imports, `wow-*` dependencies, or an explicit downstream request to use, adopt, configure, or explain Wow. Routine same-major non-breaking upgrades qualify; cross-major upgrades, known source/configuration/generated/runtime breaks, and Wow-managed storage/data changes belong to `wow-migrate`. Checkout-wide markers, negated/comparative mentions, and generic Kotlin, Java, Spring, Reactor, DDD, CQRS, or Event Sourcing vocabulary do not qualify. Otherwise state that this Skill does not apply and stop using it.

Own the complete development task. Do not route to another Wow Skill.

## Contract

- Treat the current checkout, its tests, generated contracts, and resolved dependencies as authoritative.
- Resolve the actual Wow version from the downstream build and dependency graph before applying exact symbols, defaults, or V9 rules; label version-specific conclusions unverified when the version cannot be confirmed.
- Treat commands as intent, domain events as committed facts, and sourced state as reconstructed memory.
- Keep aggregate invariants inside the aggregate boundary and external side effects outside it.
- Preserve reactive execution, serialization compatibility, module boundaries, and public contracts unless the user authorizes a breaking change.
- Keep read-only requests non-mutating; enter test-first implementation only for requested code or document changes.
- Use RED→GREEN→REFACTOR for behavior changes. If a change is not testable at the unit level, name the narrowest replacement evidence before editing.
- Report source-backed answers or changed files and behavior, exact verification commands and results, compatibility or operational risk, and remaining evidence gaps. Never replace an unavailable test with “should pass.”

## Workflow

1. **Frame / Discover**: align the outcome, writable scope, compatibility boundary, and completion evidence; resolve the actual build/module/diff plus relevant source, tests, configuration, and generated contracts.
2. **Model / Prove**: identify the owning boundary, invariant, message and failure flow; prove facts for read-only work or preserve the smallest failing test or equivalent evidence before a change.
3. **Change / Verify**: change only authorized scope; run the narrowest relevant test or check first and broaden only when the affected boundary requires it.
4. **Report** under the Contract above.

## Load one domain reference first

| Primary scope | Load |
|---|---|
| Aggregate, command, event, sourcing, lifecycle, tenant/owner routing | `references/aggregate-sourcing.md` |
| Saga, Projection, EventProcessor, retry, idempotency | `references/saga-processors.md` |
| CommandGateway, wait, delivery ambiguity, HTTP command routes | `references/command-delivery.md` |
| FilterExpression, Query DSL, snapshot aggregation, pagination, projection, sort | `references/query-read-model.md` |
| Spring Boot starter, feature capability, storage or bus routing | `references/starter-storage.md` |
| Runtime ownership, readiness, fatal handling, drain or shutdown | `references/runtime-lifecycle.md` |
| Uniqueness, reservation, rollback, or reprepare with PrepareKey | `references/prepare-key.md` |

Load a second reference only when the task genuinely crosses domains. Read only the relevant sections, then verify every exact symbol and default in the current source.

## Source discovery

For any exact annotation, property, DSL method, gateway API, or generated contract, inspect its definition, every compiler/runtime consumer, representative tests, and downstream usage. Change generated output only through its source or generator.

## Shared gates

- Do not introduce blocking or manual subscription into reactive runtime paths.
- Verify event/schema/API compatibility when changing public messages or metadata.
- Use the assertion style already established by the target module; Kotlin Wow tests normally use `me.ahoo.test.asserts.assert` and `.assert()`.
