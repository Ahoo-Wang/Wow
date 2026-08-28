---
title: Testing Wow Applications
description: After domain specifications, verify generated metadata, runtime wiring, real adapters, recovery, and security boundaries in a Wow application.
outline: deep
---

# Testing Wow Applications

This page is for teams building business applications with Wow. The domain DSL answers “does the model follow its invariants?” Application testing answers “can this model run safely with the actual wiring, protocol, and infrastructure?” They are consecutive gates and cannot replace each other.

::: tip Completion signal
Application testing is complete when the current build provides metadata evidence, at least one end-to-end business entry point, restart/redelivery evidence for production-equivalent adapters, and security negatives. The next layer is release, recovery, and observability acceptance in the target environment, not a Wow framework-repository benchmark.
:::

## Keep Three Test Categories Separate

| Scope | What it proves | What it does not prove |
| --- | --- | --- |
| [Domain DSL](./test-suite.md) | Command decisions, rejections, events, sourced state, and saga commands | Spring, HTTP, KSP packaging, real storage, or a broker |
| Application integration gates | The application's own modules, configuration, entry points, and production adapters form a closed loop | General Wow correctness or performance under other configurations |
| [Framework repository tests and benchmarks](./test-runtime.md) | Wow source, TCKs, container integration, and specified JMH workloads | Your application is releasable or has production capacity |

Business applications do not need to copy `allLocalTest`, `allContractTest`, `allIntegrationTest`, or the Codecov flag structure. Those are Wow repository maintenance tasks. Applications should set gates around their own modules and release risks.

## Application Test Ladder

| Layer | Minimum evidence | First place to investigate on failure |
| --- | --- | --- |
| Domain specification | The owning domain module's `test`/`check` passes | Invariants, events, and sourcing functions |
| Compile metadata | The annotated module generates a non-empty `META-INF/wow-metadata.json` | Whether KSP and `wow-compiler` are applied to the correct module |
| Runtime wiring | The service loads metadata and exposes expected command/state entry points | Starter capabilities, module runtime dependencies, configuration |
| Protocol vertical slice | One business command is observable from entry point to persisted result | Request contract, routing, wait semantics, error mapping |
| Real adapter | Reads, writes, and redelivery work on production-equivalent stores/brokers | Serialization, version conflict, idempotency, network boundary |
| Recovery and security | State agrees after restart/replay and unauthorized requests fail closed | Snapshots, offsets, tenant/owner/space, and ABAC |

Run the narrowest affected module every day, then expand layer by layer before merge or release. Do not skip a module task that directly identifies the failing layer merely because a root `build` is larger.

## 1. Domain Gate

Every aggregate invariant should cover at least:

- events and final state for a successful command;
- business rejection with unchanged state;
- critical lifecycle transitions;
- delete, recover, or any other enabled framework lifecycle;
- correct command or no command from a saga for positive and negative input events.

The runnable reference in this repository is:

```bash
./gradlew :example-domain:check
```

Business applications should substitute their own domain module. See the [Domain Test Suite](./test-suite.md) for the DSL.

## 2. Generated-Metadata Gate

Modules containing Wow-annotated models must apply KSP and `wow-compiler`, and the generated resources must reach the service runtime classpath. Verify the complete generation path with the repository example:

```bash
./gradlew :example-domain:clean :example-domain:kspKotlin :example-domain:test
test -s example/example-domain/build/generated/ksp/main/resources/META-INF/wow-metadata.json
```

Substitute module paths in a business application and check every annotated module. A successful KSP task is not the entire completion signal: the file must be non-empty, the service module must depend on that module, and startup evidence must show the resource was loaded. Do not hand-write or commit generated files.

## 3. Runtime and Protocol Vertical Slice

Keep an application-level test for at least one real business aggregate that closes this loop:

1. start the application with the same Spring configuration used by the production entry point;
2. send a command through the public protocol with unique aggregate and request IDs;
3. assert protocol status, command result, and error mapping;
4. read the result through a public state or query entry point;
5. prove the state came from the events just persisted, not a shared test object.

Use an existing Cart, Order, or another real application model. Do not invent a “demo aggregate” solely to test routing. In-memory adapters can provide a fast gate, but the test report must state that they do not cover production storage, brokers, restart, or authorization.

## 4. Real Adapters and Failure Semantics

Every persistence or transport capability used in production should have at least one container or isolated-environment test:

- use the same Starter capability and serialization configuration as production;
- load the same event stream after writing it to the real EventStore;
- prevent a repeated request ID or broker redelivery from repeating business side effects;
- surface optimistic-lock or version conflicts as the application defines without losing successful data;
- avoid reporting partial results as complete success when an external dependency fails;
- when SnapshotStore is enabled, make snapshot loading agree with full event replay.

Choose according to adapters the application actually owns. Do not test unused databases or brokers for the sake of completeness.

## 5. Restart, Replay, and Upgrade

In-process success is not recoverability. Prove at least:

1. write events, then stop the application;
2. restart with the same persisted data;
3. read state and compare it with the pre-restart result;
4. disable or clear snapshots and obtain the same state from full event history;
5. recover projections, consumer offsets, and compensation tasks with a recorded procedure;
6. when event revisions or serialization shapes change, provide explicit compatibility or migration evidence for old history.

Recovery evidence must retain the input-data version and environment configuration. A single success screenshot cannot support later releases without them.

## 6. Security and Isolation Negatives

Prioritize failure paths in security tests:

| Scenario | Expected result |
| --- | --- |
| Anonymous access to a protected command or query | `401` or `403` |
| Forged tenant, owner, or space | Rejected without widening scope |
| Principal lacks required ABAC tags | Fail closed; never degrade to an all-record match |
| Cross-tenant or cross-owner query | No unauthorized data returned |
| Ordinary request attempts to reach a raw query factory | No reachable entry point |

See [Data Access Control](./data-access.md#required-security-closure) for the complete boundary. Security requires the application's actual Spring Security/CoSec configuration; domain specifications cannot substitute for it.

## Pre-Release Evidence Table

| Evidence | Completion condition |
| --- | --- |
| Domain | Success, rejection, and critical lifecycle specifications pass |
| Generation | Metadata for every annotated module is non-empty and loaded by the runtime |
| Protocol | A vertical slice from public entry point to readable result passes |
| Infrastructure | Production-equivalent adapter write, redelivery, conflict, and failure paths pass |
| Recovery | Restart and replay without snapshots produce consistent state |
| Security | Authentication, tenant isolation, and ABAC negatives all fail closed |
| Operations | Backup, recovery, rollback, traces, metrics, and alerts have target-environment evidence |

Move to [Framework Tests and Benchmarks](./test-runtime.md) only when changing Wow framework source, implementing an adapter, or investigating framework performance. Green framework CI or historical benchmark figures cannot replace this application evidence table.
