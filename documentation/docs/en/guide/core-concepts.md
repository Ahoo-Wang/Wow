---
title: Core Concepts
description: Use stable terms for Wow commands, aggregates, events, state, waits, projections, sagas, and recovery.
outline: deep
---

# Core Concepts

This page only standardizes vocabulary and points to authoritative pages. The capability guides own procedures, runtime state machines, and complete examples.

```text
command payload
  → CommandMessage envelope
  → aggregate decision
  → domain event payloads in a DomainEventStream
  → sourced aggregate state
  → projections / sagas / other processors
```

## Vocabulary Summary

| Term | Stable meaning in Wow | Main artifact |
| --- | --- | --- |
| Bounded context | A named business-language boundary containing aggregate definitions | `@BoundedContext` |
| Aggregate | One consistency boundary identified by context, aggregate name, tenant, and ID | `NamedAggregate`, `AggregateId` |
| Command | An imperative payload requesting a state change | data class/object, `@CreateAggregate`, `@CommandRoute` |
| Command message | Runtime envelope carrying the command plus identity, request, version, headers, and routing metadata | `CommandMessage<C>` |
| Command aggregate root | Domain object that checks invariants and returns events | `@AggregateRoot`, `@OnCommand` |
| State aggregate root | State object rebuilt only by sourcing events | `@OnSourcing` |
| Domain event | Immutable business-fact payload | data class/object; `@Event` for explicit metadata |
| Domain event envelope | Runtime event plus aggregate, command, sequence, revision, and time metadata | `DomainEvent<T>` |
| Event stream | Ordered batch of events produced by one aggregate command | `DomainEventStream` |
| Event store | Authoritative append/load contract for aggregate event streams | `EventStore` |
| Snapshot | Replaceable derived checkpoint used to accelerate aggregate restoration | `SnapshotStore` |
| Wait stage | A caller-selected definition of command completion | `SENT`, `PROCESSED`, `SNAPSHOT`, `PROJECTED`, and others |
| Projection | Event processor that maintains a read model | `@ProjectionProcessor`, `@OnEvent` |
| Saga | Event processor that coordinates across aggregates by sending later commands | `@StatelessSaga`, `@OnEvent` |
| Event compensation | Observable recording, scheduling, and retry of failed event-processing work | compensation records, `RetrySpec` |

## Key Boundaries

- A command handler decides from current state and returns events; only deterministic, side-effect-free sourcing functions change state.
- Event streams appended to EventStore are authoritative history. Snapshots and projections are rebuildable derived data.
- `SENT`, `PROCESSED`, `SNAPSHOT`, and function-scoped downstream stages prove different boundaries and cannot substitute for one another.
- A Processor owns ordinary side effects, a Saga sends cross-aggregate commands, and event compensation retries a failed function. A business reverse action remains an explicit domain command.
- Projection completion does not automatically prove query visibility. Execute the real query when the user-visible result is the contract.

## Authoritative Reading Paths

| Capability | Authoritative pages |
| --- | --- |
| Domain boundaries, history, and restoration | [Domain Model](./domain/), [Aggregate and Invariants](./domain/aggregate.md), [Event Sourcing](./domain/event-sourcing.md), [Event Evolution](./domain/event-evolution.md), [Snapshots](./domain/snapshot.md), [Aggregate Lifecycle](./domain/lifecycle.md) |
| Command definition, invocation, and results | [Commands](./command/), [Define Commands](./command/definition.md), [Send Commands](./command/sending.md), [API Client](./command/api-client.md), [Completion Semantics](./command/completion.md), [Failures and Idempotency](./command/reliability.md) |
| Command runtime internals | [Command Processing Pipeline](./command/internals/pipeline.md), [Command Wait Runtime](./command/internals/wait-runtime.md), [Command Transport and Routing](./command/internals/transport.md) |
| Event processing and cross-aggregate collaboration | [Events and Collaboration](./event/), [Event Processor](./event/processor.md), [Saga](./event/saga.md), [Event Compensation](./event/compensation.md), [Event Dispatch Pipeline](./event/dispatch.md) |
| Read models and queries | [Projection](./projection.md), [Query Service](./query.md), [Data Access Control](./data-access.md) |
