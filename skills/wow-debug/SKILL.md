---
name: "wow-debug"
description: "Diagnose observed failures in downstream applications' Wow command, sourcing, event-processing, projection, query, or runtime pipelines. Never activate for the Wow framework repository itself. Require the failure path to involve Wow, established by explicit target wording or scoped me.ahoo.wow imports or wow-* dependencies; checkout-wide markers and generic DDD/CQRS terms are insufficient. Use for diagnosis-only and diagnose-and-fix. Do not use for non-Wow scopes, proactive review/development, or migration/data-cutover failures."
---

# Debug Wow Failures

## Scope gate

Before diagnosing, confirm that the target is a downstream application rather than the Wow framework repository itself. Then require the failure path to involve a Wow pipeline and scoped source to contain `me.ahoo.wow` imports or `wow-*` dependencies, or the request to explicitly identify the failing Wow application behavior. Framework-repository modules, markers elsewhere in a checkout, negated/comparative mentions, and shared DDD/CQRS vocabulary do not qualify. If no downstream scoped Wow evidence exists, state that this Skill does not apply and stop using it.

Own the complete diagnosis or diagnose-and-fix task. Do not route to another Wow Skill.

## Contract

- Keep diagnosis-only requests read-only.
- Attempt the narrowest safe, non-mutating reproduction before explaining. If reproduction is unavailable, unsafe, or outside authorization, state that limit and use the strongest available evidence.
- Prefer one falsifiable hypothesis over a list of speculative causes.
- Compare with a working path in the same checkout.
- Do not add retries, relax assertions, or change annotations before proving where the pipeline breaks.

## Diagnostic workflow

1. **Capture**: record the exact command, request, event, test, configuration, log, stack trace, timing, and expected behavior.
2. **Reproduce**: run the narrowest safe, non-mutating reproducer and preserve its exact result. If that is impossible or unauthorized, record the constraint and continue from logs, traces, source, tests, and configuration without inventing a reproducer.
3. **Locate**: use `references/pipeline-map.md` to identify the first incorrect stage.
4. **Discover**: inspect definitions, metadata generation, registration, routing, delivery, invocation, persistence, and observation only as far as the symptom requires.
5. **Hypothesize**: state “stage X fails because Y” and identify evidence that would falsify it.
6. **Test**: run the smallest diagnostic or focused test that distinguishes the hypothesis.
7. **Conclude**: name the confirmed failing stage, cause, affected boundary, and remaining unknowns.

Load `references/handler-discovery.md` only when a handler is missing, unregistered, unmatched, or unselected. Do not run its metadata/JAR discovery path for ordinary configuration, Query DSL, lifecycle, or assertion failures.

## Diagnose-and-fix

Only when the user requests a fix:

1. Preserve the reproducer or add a failing regression test.
2. Make the smallest change that addresses the confirmed cause.
3. Run the focused reproducer and relevant regression checks.
4. Inspect the resulting diff for altered contracts or adjacent pipeline risk.

Do not combine independent hypotheses into one patch.

## Completion

For diagnosis-only work, report the reproducer or the precise reason it was unavailable, the failing stage, confirmed evidence, affected scope, and unknowns without claiming a fix. For an authorized fix, also report pre-fix evidence, changed files and behavior, exact verification commands and results, and residual operational risk.
