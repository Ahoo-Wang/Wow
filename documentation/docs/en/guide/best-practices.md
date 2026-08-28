---
title: Production Best Practices
description: Apply modeling, non-blocking execution, idempotency, snapshots, compensation, testing, and production evidence along Wow command stages.
outline: deep
---

# Production Best Practices

Wow provides commands, event sourcing, message handling, and observable wait stages. The application still owns business invariants, external effects, backend topology, and recovery results. Practices must map to a concrete runtime stage and repeatable evidence. Generic operational checklists unrelated to the Wow flow are outside this page.

## Practice Map

| Wow boundary | Preferred practice | Evidence to retain |
| --- | --- | --- |
| Command → Aggregate | Commands express intent; aggregates protect invariants | `AggregateSpec` success, rejection, and concurrency branches |
| Aggregate → EventStore | Change state only through domain events | Version continuity, conflict, and request-id tests |
| EventStore → DomainEventBus | Retry-safe handlers and idempotent side effects | Redelivery/failure injection and broker-ACK evidence |
| StateEvent → Snapshot | Use `strategy: all` for current-state queries | `SNAPSHOT` read-after-write and full-replay reconciliation |
| Projection/Processor/Saga | Wait for an exact function target | Function identity, lag, compensation, and side-effect reconciliation |
| WowRuntime | Remove ingress, quiesce, then stop in reverse order | Shutdown result and remaining lag within the termination window |

## Model Business Decisions, Not Data Updates

A command names a business action, a command aggregate decides whether it is allowed, a domain event records the accepted fact, and a state aggregate deterministically applies events only in sourcing functions.

| Element | Application responsibility | Avoid |
| --- | --- | --- |
| Command | Carry decision input and a stable request ID | Arbitrary field-update APIs |
| Command aggregate | Enforce invariants and return domain events | Mutating a public state store directly |
| Domain event | Record an evolvable past-tense business fact | Treating a temporary DTO as an event contract |
| State aggregate | Rebuild state deterministically in event order | Network, time, or randomness during sourcing |

An aggregate boundary covers only invariants that require one atomic decision. Connect cross-aggregate work with events and sagas instead of expanding a shared aggregate to remove one asynchronous stage.

## Preserve the Reactive Boundary

`CommandGateway`, buses, dispatchers, EventStore, projections, and sagas compose `Mono`/`Flux`. A `block()`, synchronous database driver, or hidden thread wait in the core path consumes processing resources and prevents `wow.shutdown-timeout` from proving admitted work can drain.

When an external SDK truly cannot be non-blocking, isolate it at an application-adapter boundary on a capacity-bounded scheduler and observe queueing, timeouts, and rejection. `@Blocking` is an isolation marker; it does not repair a slow query, unbounded concurrency, or non-cancellable I/O.

## Wait for the Business Outcome You Need

| Stage | Proves | Does not prove |
| --- | --- | --- |
| `SENT` | CommandBus accepted the send | Aggregate execution |
| `PROCESSED` | Command filter chain completed, including aggregate decision, EventStore append, and DomainEventBus send | Downstream consumers completed |
| `SNAPSHOT` | Snapshot Dispatcher completed this StateEvent | All projections completed; `version_offset` wrote a new snapshot |
| `PROJECTED` | Target projection completed; without a function target, the last-projection signal arrived | Event processors or sagas completed |
| `EVENT_HANDLED` | Target event-processing function completed | Saga-derived commands completed |
| `SAGA_HANDLED` | Target saga handled the source event and any derived command was sent/accepted | Downstream aggregate completion or distributed transaction commit |

Function stages should name `contextName`, `processorName`, and `functionName` so an unrelated processor cannot satisfy the contract. `WaitPlan.withTimeout` bounds only the caller's local wait; it is not propagated in the command header. A timeout means the result is unknown, not that the command did not execute. Select the narrowest stage that proves the API contract.

## Make Retry, Concurrency, and LocalFirst Semantics Explicit

| Mechanism | Purpose | Boundary |
| --- | --- | --- |
| `requestId` | Identifies one logical command across retries | Reuse it for the same business action; a new ID is a new command |
| `aggregateVersion` | Rejects a write based on stale state | Omit only when the business accepts any current version |
| Aggregate retry | Retries aggregate failures classified as recoverable | Bounded backoff; persistent conflicts require hot-spot or boundary work |
| LocalFirst | Removes broker round-trip after local admission | Not exactly once; handler failure after admission does not re-enable the distributed copy |

Idempotency must extend through external effects. Use a business key, event ID, or target version and read current effect state before retrying. Broker redelivery, caller timeout retries, and operator compensation are independent duplicate sources; test all three.

## Use Snapshots as the Default Query Store

For current state of one aggregate, prefer `strategy: all` with a query-capable SnapshotStore instead of copying the same state into another projection. MongoDB and Elasticsearch provide SnapshotQueryService. Redis and in-memory stores can save/load snapshots but provide no general dynamic-query implementation.

| Requirement | Selection | Acceptance |
| --- | --- | --- |
| Current state and read-after-write | `all` + the same query backend | Wait for `SNAPSHOT`, then query the target version |
| Aggregate-load optimization with tolerated query staleness | `version_offset` | Record the allowed version gap and sample full EventStore replay |
| Cross-aggregate, denormalized, or external read model | Projection | Exact `PROJECTED` target, idempotency, rebuild, and reconciliation |

A snapshot is a derived checkpoint; EventStore remains authoritative aggregate history. Query indexes, tenant/owner/space filtering, and authorization remain application-owned.

## Orchestrate and Compensate Deliberately

A saga expresses cross-aggregate orchestration, not an ACID distributed transaction. `SAGA_HANDLED` covers the source-event function and possible command-send boundary. If a caller needs downstream aggregate completion, use a wait chain or read a verifiable state instead of expanding that stage's meaning.

When automatic retries are exhausted or an error is unrecoverable, compensation data should retain the target function, error, retry state, and operator decision. Before resending, prove handler idempotency and distinguish resending a domain event, a reconstructed state event, or one failed function. Their blast radii differ.

## Test Behavior at the Narrowest Useful Layer

| Test layer | Minimum assertion | Tool |
| --- | --- | --- |
| Aggregate | Command acceptance/rejection, event, state | `AggregateSpec` |
| Saga | Commands and branches from a source event | `SagaSpec` |
| Adapter | EventStore/SnapshotStore/bus/query contracts | `wow-tck` |
| Application integration | Generated metadata, serialization, real bus/store, Spring wiring | Production-like integration test |
| Operations | Redelivery, rebuild, backup/restore, reconciliation, shutdown | Isolated drill |

Reproduce at the narrowest layer first, then add integration tests only for infrastructure behavior. String assertions or mocks do not prove actual MongoDB, Redis, Kafka, or Elasticsearch behavior.

## Production Readiness Checklist

| Gate | Pass condition | Evidence |
| --- | --- | --- |
| `SENT` | Target bus send, authentication, topic/Stream, and error path verified | Broker tests, ACLs, lag/ACK records |
| `PROCESSED` | Aggregate specs, EventStore concurrency/idempotency, and event evolution pass | Test reports and version/revision samples |
| `SNAPSHOT` | Strategy, StateEvent consumption, query backend, and rebuild pass | Read-after-write, full-replay reconciliation, query plan |
| Function stages | Target matching, redelivery idempotency, and compensation are operable | Function identity, failure injection, compensation records |
| HTTP | Actual routes are protected by authorization and query guards | Runtime OpenAPI and authorization/rate-limit tests |
| Lifecycle | Ingress removal, quiescence, reverse stop, and fatal close meet budget | Rolling-shutdown timeline and remaining lag |
| Recovery | EventStore, derived state, offsets, and compensation pass isolated restore | Checksums, RPO/RTO, business reconciliation |

Only evidence from the target topology and production-like data volume supports a production claim. Framework module checks prove source regression; they do not prove capacity, deployment, or recovery by themselves.

## Related Pages

- [Core Concepts](./core-concepts.md)
- [Aggregate Modeling](./modeling.md)
- [Command Gateway](./command-gateway.md)
- [Snapshot](./snapshot.md)
- [Query](./query.md)
- [Distributed Transactions (Saga)](./saga.md)
- [Event Compensation](./event-compensation.md)
- [Backup, Restore, and Replay](./recovery.md)
- [Test Suite](./test-suite.md)
- [Observability](./advanced/observability.md)
