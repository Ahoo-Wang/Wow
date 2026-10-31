---
name: "wow-develop"
description: "Build, review, debug or explain Wow V10 services in downstream Kotlin/Java code with me.ahoo.wow imports, wow-* dependencies or Wow requests: aggregates, rejections, sagas, projections, reactors, failures, tests, Spring Boot hosting; findings, merge readiness; root cause, fixed when authorized. Includes first adoption. Not the Wow framework repository, generic DDD/CQRS, 9.x services (the v9 plugin), or 9.x-to-V10 migration (wow-migrate)."
---

# Develop, Review and Diagnose Wow V10 Services

The job: someone is building or running a service on Wow V10 and needs a change made, a change judged, or a failure explained. The deliverable is one of: code with its tests passing; findings with evidence and a merge verdict; or the failing stage, its cause and, when authorized, the fix with a regression test. This Skill owns the whole task in whichever mode it takes.

## Scope gate

Use only for a downstream application that builds, or is adopting, Wow V10. Require scoped `me.ahoo.wow` imports, `wow-*` dependencies, explicit failing Wow behavior, or an explicit request to use, adopt, configure, review or explain Wow. Not this Skill:

- **The Wow framework repository and its modules.**
- **A 9.x service** (`wow-spring-boot-starter`, `wow-core`, `@StatelessSaga`, `@EventProcessor`, `CommandGateway.sendAndWait`, `wow-metadata.json`): say it is 9.x and that the 9.x Skills ship on the `v9` branch's plugin; do not apply V10 APIs to it.
- **A 9.x service moving to V10**, its data or its cut-over: `wow-migrate`.
- **TypeScript code** calling a service: `wow-client`. **Data answers** from a running service: `wow-data-query`. **Views**: `wow-view-definition`, `wow-view-host`.
- Checkout-wide markers, negated or comparative mentions, release or build tooling, dashboards, and generic Kotlin, Java, Spring, Reactor, DDD, CQRS or Event Sourcing vocabulary.

Otherwise say this Skill does not apply and stop using it.

## Recognize V10 before anything else

V10 when the build or sources show: the Gradle plugins `id("me.ahoo.wow")`, `id("me.ahoo.wow.api")` or `id("me.ahoo.wow.service")`, or `me.ahoo.wow:wow-bom`, of a 10.x version; `wow-kernel`, `wow-spring-boot`, `wow-runtime`, `wow-postgres`, `wow-mongo`; imports of `me.ahoo.wow.api.decide.Decision`, `me.ahoo.wow.api.decide.DecideScope`, `me.ahoo.wow.api.subscribe.StateEvent`, `me.ahoo.wow.test.aggregateSpec`; annotations `@Saga`, `@Reactor`, `@Projection`, `@OnRejected`, `@ErrorCode`; resources `META-INF/wow/manifest/<module>.json`. Pin the exact Wow version from the build and its resolved dependencies; every symbol, default, key and route in these references is verified against that version's source, or labelled unverified. A V10 module that depends on a 9.x module is a defect.

## Contract

- The current checkout, its tests, its Manifest and its resolved dependencies are the authority; the references say where to look, not what is true today.
- Commands are intent, events are committed facts, state is evolved memory. A decide function decides from the command and the state and never mutates; an evolve function applies one event and does no I/O; side effects live in sagas, reactors and projections.
- Keep handlers non-blocking (`suspend`, or Reactor types with `wow-reactor`), events free of personal data (they are permanent), and the tenant, owner and version out of command bodies.
- **No checkout is no reason to stop.** When the project, the sources or a shell are not available (only a diff, a snippet or a description), still deliver: write the test, the fix or the code against what was shown, mark each name to confirm in the project, and give the commands to run and what they should show. Never answer with only a plan or a question.
- Keep read-only requests (review, diagnosis, lookup) read-only: no edits, comments, approvals, merges or remote changes without explicit authorization.
- Report the answer or the changed files and behavior, each verification command with its result, compatibility or operational risk, and every evidence gap. Never write "should pass" for a test that did not run.

## Modes

**Develop or explain.** Frame the outcome, the writable scope, the compatibility boundary (events, rejection codes, Manifest, configuration) and the completion evidence. Find the owning context, aggregate, invariant, event and failure flow. Write the test first with the V10 test DSL (`references/testing.md`), see it fail, make it pass, refactor. Run the narrowest check first (`./gradlew :<domain>:test`), then the module's `check`, which runs the Manifest compatibility check.

**Review** (findings, merge readiness). Resolve the diff range (working tree, base, merge-base, touched modules). Read the changed files with their callers, processors, tests, configuration and generated Manifest. Check behavior against `references/review-rubric.md` before style. Run the narrowest checks when feasible; label the rest unverified. Lead with findings by severity, each with file and line, impact, trigger and the smallest correct direction; if none remain, say so. List every write, remote, approval or merge action left undone.

**Review-and-fix** (only when the user authorizes fixes): finish the findings first; fix only the authorized ones, each with a failing test first; review the new diff again from the base. "Review and fix" is not permission to fix unrelated issues.

**Diagnose** (failure, hang, wrong state, a timeout, a 4xx or 5xx). Capture the exact command, request, headers, response problem, log, stack trace, timing and expected behavior. Run the narrowest safe, non-mutating reproducer; when that is unavailable or unauthorized, say so and work from logs, the problem's `code`, source, tests and configuration. Locate the first wrong stage with `references/diagnose.md`, state one falsifiable hypothesis, run the smallest check that distinguishes it, and conclude with the stage, the cause, the boundary and what is still unknown. Do not add retries, relax assertions or change annotations before the stage is proven. The ops routes and console are read-only evidence; resuming or skipping a parked entry is a production write that needs its own authorization.

**Diagnose-and-fix** (only when a fix is requested): keep the reproducer or add a failing regression test, make the smallest change at the confirmed cause, rerun the reproducer and the relevant checks, and inspect the diff for changed contracts.

## Load by the job

| The work is about | Load |
|---|---|
| Aggregates, commands, events, state, rejections and error codes, creation and lifecycle, revisions and upgrades, retired events, reservations, owner, space and tags, scheduled commands | `references/model.md` |
| Sagas, projections, reactors, handlers by event type, `@OnRejected`, retries, parking and the failure ledger, start positions, idempotency | `references/processes.md` |
| Another context: its published events, sending it commands, synchronous calls | `references/cross-context.md` |
| Tests: aggregate, saga, projection and reactor specs, forks, time, server tests | `references/testing.md` |
| Build and modules, the Spring Boot host, backends, configuration keys, callers, request facts, the internal port | `references/hosting.md` |
| State reads, queries, HTTP routes and headers, the Manifest and OpenAPI, clients over HTTP | `references/reads.md` |
| A failure: a symptom to its first wrong stage | `references/diagnose.md` |
| A review | `references/review-rubric.md` |

Load the one reference the job needs first, a second only when the task crosses into it, and read only the relevant sections. Verify every exact symbol and default in the target's source before relying on it.

## Shared gates

- An exact annotation, property, DSL method or route: read its definition, the compiler or runtime code that consumes it, a representative test and the downstream usage. Change generated output (the Manifest, `<root>Model`, `<processor>Handlers`, a TypeScript client) only through its source.
- A change to an event, a command, a rejection code or the state's shape is a contract change: the module's `check` compares the Manifest with its baseline and fails on a break. Do not update the baseline to silence it without the user's decision.
- Use the assertion style of the module; Kotlin Wow tests use `me.ahoo.test.asserts.assert` and `.assert()`.
- Guides: https://wow.ahoo.me/guide/ for the target's version; examples in the Wow repository: `examples/quickstart` (one aggregate, its spec and server test) and `examples/eshop` (five services, sagas across contexts, end-to-end tests).
