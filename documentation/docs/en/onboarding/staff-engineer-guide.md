---
title: Staff Engineer Guide
description: Decide whether a Wow boundary change is worth accepting using architecture, compatibility, and operational evidence.
---

# Staff Engineer Guide

This page answers one question: **should this architecture or runtime boundary change be accepted?**

The default is to preserve the current boundary. Expand a public contract, dependency, or runtime responsibility only when evidence shows the current design cannot meet the need, the smallest alternative is clear, and compatibility plus operational cost can be verified.

## Verified architecture inputs

Establish the decision baseline from the current repository:

- `wow-api` owns pure API contracts; `wow-core` owns CQRS, event sourcing, messaging, projection, saga, and runtime behavior; `wow-spring*` owns Spring wiring; storage and transport stay in dedicated modules. [`settings.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/main/settings.gradle.kts) is the module inventory.
- Runtime paths remain Reactor `Mono`/`Flux`; core dispatch, event store, projection, saga, and transport paths must not introduce blocking.
- After a command appends its event stream, downstream publication, projections, sagas, and snapshots have distinct completion and failure boundaries. `SENT`, `PROCESSED`, `SNAPSHOT`, and `PROJECTED` are not interchangeable.
- Event history is authoritative; snapshots accelerate restoration; projections are derived read models; compensation is controlled retry or replay, not database rollback.
- Module checks, TCKs, integration tests, and benchmarks prove only their stated environment and scope. They do not establish a production SLA, global ordering, or an arbitrary deployment topology.

[Architecture](../guide/advanced/architecture.md), [Runtime Lifecycle](../guide/advanced/runtime-lifecycle.md), [Data Flow](../guide/advanced/data-flow.md), and [Module Dependencies](../guide/advanced/module-dependencies.md) own the detailed contracts.

## Architecture and operating trade-offs

| Decision | Possible benefit | Cost that must be owned | Minimum evidence |
|---|---|---|---|
| Expand a `wow-api` contract | Let downstream code express a new capability directly | Source, binary, and wire compatibility need separate decisions | API consumers, compilation and contract tests; JVM ABI and generated-contract comparison when relevant |
| Move a module responsibility or add a dependency | Reuse implementation or simplify wiring | Dependency direction, BOM, feature capability, and publication surface grow | Producer/consumer Gradle checks and the resolved dependency graph |
| Move from a local to a distributed bus | Cross-process delivery and scaling | Delivery ambiguity, ordering, ack, retry, DLQ, and operator ownership | Real broker integration tests, failure drills, and replay/reconciliation plan |
| Change an event or generated schema | Evolve a business contract | Old-event reads, downstream consumers, and rollback can fail | Old-data/old-request contract tests, generated diff, and upgrade path |
| Wait for a later stage | Give callers a stronger visibility signal | Latency, timeout, and cross-component failure surface increase | Real stage tests plus timeout/cancellation behavior; a stage name is not proof |
| Add a store or cache path | Meet persistence or query needs | Consistency, backup, restore, indexing, and capacity responsibilities grow | Backend TCK/integration tests, backup/restore, and replay evidence |
| Add a security adapter | Propagate identity or constrain queries | Header propagation does not complete authentication, authorization, or tenant isolation | Application security configuration, rejection paths, and resource-scope tests |
| Claim a performance improvement | Address an identified bottleneck | Results depend on hardware, data, parameters, and version | Reproducible benchmark baseline; smoke cannot establish capacity |

If a cost has no owner, it is not deferred documentation work; the design has not been operationally admitted.

## Decision process

### 1. Locate the single owner

List public types, runtime consumers, Spring wiring, backend implementations, tests, and generated artifacts. Prefer a root-cause change in the existing shared boundary; do not create a parallel abstraction for one caller.

### 2. Separate four contracts

Record four independent conclusions:

1. **Source**: can downstream source still compile?
2. **Binary**: can existing JVM binaries still link?
3. **Wire / persisted data**: do old requests, events, snapshots, queries, and generated clients still work?
4. **Operational**: do deployment, replay, recovery, shutdown, monitoring, and rollback still hold?

Passing one category proves none of the others. If history or incompatible writes are involved, move to [Migration](../guide/migration.md) instead of treating the change as a dependency bump.

### 3. Choose the smallest reversible design

Reuse existing contracts, backend-native semantics, and feature capabilities. Add configuration only when deployments genuinely need a choice; one implementation does not need a speculative interface, factory, or compatibility bridge.

### 4. Define completion evidence

Before implementation, write the verification matrix: owning module, consumers, TCK/integration tests, old contracts, generated diff, benchmark, or operational rehearsal. Mark production evidence that cannot be established locally as missing; do not substitute a unit test.

## Acceptance gate

Recommend the boundary change only when all conditions hold:

- the need and non-goals are explicit, and the current boundary is demonstrably insufficient;
- the design is the smallest change that meets the need, with no unused extension points;
- source, binary, wire, and operational impact have separate conclusions;
- event evolution, idempotency, ordering, wait stage, recovery, and security have owners;
- relevant module checks, contracts/TCKs, integration tests, and generated-artifact checks ran;
- rollback boundaries and missing deployment/data evidence are in the decision record;
- the final diff contains only approved scope.

## Prioritized next path

1. **Boundary unchanged**: implement the smallest change through the [Contributor Guide](./contributor-guide.md).
2. **Runtime or storage change**: verify [Best Practices](../guide/best-practices.md) and [Recovery](../guide/recovery.md) first.
3. **Breaking contract or historical-data change**: use [Migration](../guide/migration.md) and establish reconciliation plus rollback evidence before cutover.
