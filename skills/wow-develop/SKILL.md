---
name: "wow-develop"
description: "Build, review, debug or explain Wow behavior in downstream Kotlin/Java services: aggregates, commands, sagas, projections, queries, starter configuration; diff findings and merge readiness; failing-pipeline root cause, fixed when authorized. Covers first adoption and routine same-major upgrades. Needs me.ahoo.wow imports, wow-* dependencies or an explicit Wow request. Excludes the Wow framework repository, generic DDD/CQRS, and breaking migrations."
---

# Develop, Review and Debug Wow Services

## Scope gate

Use only for a downstream application; the Wow framework repository and its modules never qualify. Require scoped `me.ahoo.wow` imports, `wow-*` dependencies, explicit failing Wow behavior, or an explicit downstream request to use, adopt, configure, review, or explain Wow. Routine same-major non-breaking upgrades qualify; cross-major upgrades, known source/configuration/generated/runtime breaks, Wow-managed storage/data changes, and failures or reviews of such a migration or data cutover belong to `wow-migrate`. Checkout-wide markers, application scopes without Wow semantics, negated/comparative mentions, release or build tooling, dashboards, and generic Kotlin, Java, Spring, Reactor, DDD, CQRS, or Event Sourcing vocabulary do not qualify. Otherwise state that this Skill does not apply and stop using it.

Own the whole task, whichever mode it takes; do not hand it to another Wow Skill.

## Contract

- Treat the current checkout, its tests, generated contracts, and resolved dependencies as authoritative.
- Resolve the actual Wow version from the downstream build and dependency graph before applying exact symbols, defaults, or V9 rules; label version-specific conclusions unverified when the version cannot be confirmed.
- Treat commands as intent, domain events as committed facts, and sourced state as reconstructed memory. Keep aggregate invariants inside the aggregate boundary and external side effects outside it.
- Preserve reactive execution, serialization compatibility, module boundaries, and public contracts unless the user authorizes a breaking change.
- Keep read-only requests (review, diagnosis, lookup) read-only: no edits, comments, approvals, merges, or remote changes without explicit authorization.
- Report source-backed answers or changed files and behavior, exact verification commands and results, compatibility or operational risk, and remaining evidence gaps. Never replace an unavailable test with “should pass.”

## Modes

**Develop or explain.** Frame the outcome, writable scope, compatibility boundary, and completion evidence; resolve the build, module, relevant source, tests, configuration, and generated contracts. Identify the owning boundary, invariant, message and failure flow. For behavior changes use RED→GREEN→REFACTOR; if a change is not unit-testable, name the narrowest replacement evidence before editing. Run the narrowest relevant check first and broaden only when the boundary requires it.

**Review** (findings, merge readiness). Resolve the diff range: worktree state, requested base, merge-base, staged/unstaged changes, touched modules. Read changed files with their callers, consumers, neighbors, tests, configuration, and generated outputs. Check Wow semantics and observable behavior before style against `references/review-rubric.md`, verifying exact APIs in the checkout. Run the narrowest checks when feasible; label the rest unverified. Lead with findings ordered by severity, each with tight file/line evidence, impact, trigger, and the smallest correct direction; if none remain, say so. List every intentionally omitted write, remote, approval, or merge action.

**Review-and-fix** (only when the user authorizes fixes): finish the findings pass first; select only authorized findings; add a failing test or equivalent pre-fix evidence; implement one coherent fix pass; verify; review the new diff again from the requested base; repeat only for new issues inside the authorized scope. “Review and fix” is not permission to fix unrelated pre-existing issues.

**Diagnose** (failure, hang, wrong state, reproducer). Capture the exact command, request, event, test, configuration, log, stack trace, timing, and expected behavior. Run the narrowest safe, non-mutating reproducer and keep its exact result; if that is unavailable, unsafe, or unauthorized, say so and continue from logs, traces, source, tests, and configuration without inventing one. Locate the first incorrect stage with `references/pipeline-map.md`, comparing with a working path in the same checkout. State one falsifiable hypothesis (“stage X fails because Y”), run the smallest test that distinguishes it, and conclude with the failing stage, cause, affected boundary, and unknowns. Do not add retries, relax assertions, or change annotations before proving where the pipeline breaks. Load `references/handler-discovery.md` only when a handler is missing, unregistered, unmatched, or unselected — not for configuration, Query DSL, lifecycle, or assertion failures.

**Diagnose-and-fix** (only when a fix is requested): keep the reproducer or add a failing regression test; make the smallest change that addresses the confirmed cause; run the reproducer and relevant regression checks; inspect the diff for altered contracts or adjacent pipeline risk. Do not combine independent hypotheses into one patch. Report pre-fix evidence alongside the fix.

## Load one domain reference first

| Primary scope | Load |
|---|---|
| Aggregate, command, event, sourcing, lifecycle, tenant/owner routing | `references/aggregate-sourcing.md` |
| Saga, Projection, EventProcessor, retry, idempotency | `references/saga-processors.md` |
| CommandGateway, wait, delivery ambiguity, HTTP command routes | `references/command-delivery.md` |
| Query DSL, Snapshot/EventStream, QueryPolicy, Schema, pagination or HTTP query boundaries | `references/query-read-model.md` |
| Spring Boot starter, feature capability, storage or bus routing | `references/starter-storage.md` |
| Runtime ownership, readiness, fatal handling, drain or shutdown | `references/runtime-lifecycle.md` |
| Uniqueness, reservation, rollback, or reprepare with PrepareKey | `references/prepare-key.md` |

Add `references/review-rubric.md` for a review and `references/pipeline-map.md` for a diagnosis. Load a second domain reference only when the task genuinely crosses domains; read only the relevant sections, then verify every exact symbol and default in the current source.

## Source discovery and shared gates

- For any exact annotation, property, DSL method, gateway API, or generated contract, inspect its definition, every compiler/runtime consumer, representative tests, and downstream usage. Change generated output only through its source or generator.
- Do not introduce blocking or manual subscription into reactive runtime paths.
- Verify event/schema/API compatibility when changing public messages or metadata.
- Use the assertion style already established by the target module; Kotlin Wow tests normally use `me.ahoo.test.asserts.assert` and `.assert()`.
- Guides: https://wow.ahoo.me/guide/ (testing: https://wow.ahoo.me/guide/test-suite.html, troubleshooting: https://wow.ahoo.me/guide/troubleshooting.html).
