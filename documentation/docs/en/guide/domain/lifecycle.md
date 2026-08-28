---
title: Aggregate Lifecycle
description: Explain StateAggregate creation, restoration, ordered sourcing, metadata evolution, and failure boundaries.
outline: deep
---

# Aggregate Lifecycle

## Create or Restore State

`StateAggregateFactory` creates an uninitialized state aggregate from state metadata and a complete `AggregateId`. Load an existing aggregate through `StateAggregateRepository`:

- For the latest version, `EventSourcingStateAggregateRepository` first tries to materialize the latest snapshot. Without one, it creates an empty aggregate and loads events from `expectedNextVersion`.
- For a specified historical version or time, restoration starts from an empty aggregate and must not use a latest snapshot that may be later than the target.
- `EventStoreStateAggregateRepository` always loads events from an empty aggregate and never uses snapshots.

Application code should not compose snapshot and event reads itself. Event streams remain authoritative history; a snapshot is only a replaceable restoration starting point. See [Event Sourcing](./event-sourcing.md) and [Snapshots](../domain/snapshot.md).

## State Sourcing Lifecycle

`SimpleStateAggregate.onSourcing` processes one `DomainEventStream` in this order:

1. If the stream satisfies `ignoreSourcing`, return immediately without changing state, version, or metadata.
2. Verify that the stream's complete `AggregateId` equals the current aggregate identity.
3. Verify that the stream version equals `expectedNextVersion`.
4. Update version, owner, space, eventId, operator, and eventTime; the initial version also records firstOperator and firstEventTime.
5. Process built-in metadata events and invoke matching state sourcing functions in stream order.
6. If state implements `StateAggregateTagsExtractor`, extract tags again after the complete stream has been processed.

When an event has no matching sourcing function, its body is ignored but the event-stream version still advances. This lets notification events that do not change current state preserve continuous history; it also means replay tests must detect missing state transitions.

## Deletion, Recovery, Owner, and Space

Deletion and recovery are state-metadata changes; they do not delete or undo history:

| Event or stream information | State change |
| --- | --- |
| `AggregateDeleted` | `deleted = true` |
| `AggregateRecovered` | `deleted = false` |
| `OwnerTransferred` | Update owner to the event's `toOwnerId` |
| `SpaceTransferred` | Update space to the event's `toSpaceId` |
| Non-blank stream owner/space | Update current owner/space before processing event bodies |
| `ResourceTagsApplied` | Update tags; a state extractor may override them after the stream |

The command side decides whether deleted state can accept a normal or recovery command. The state side only rebuilds `deleted`, owner, space, and tags from events that happened. Read and authorization policies must still define whether deleted state is visible.

## Version, Concurrency, and Order

`expectedNextVersion` is the only event-stream version the current aggregate can accept next. An identity mismatch is rejected, a discontinuous version throws `SourcingVersionConflictException`, and events within a stream are applied in `eventStream` iteration order; sourcing does not reorder or validate them by numeric `sequence`.

This gives deterministic restoration within one aggregate object; it is not a cross-instance global lock. Persistent concurrency remains the EventStore append version constraint. A caller can include `aggregateVersion` in a command for compare-and-set semantics, but aggregate version cannot replace idempotency for external side effects.

## Failure Locations Inside the Aggregate

| Failure location | Current result | Recovery direction |
| --- | --- | --- |
| State construction or snapshot materialization | No usable aggregate exists yet | Repair constructors, serialization, or snapshot data; rebuild from event history when necessary |
| Event read or upgrade | The current stream has not completed sourcing | Repair storage reads, type resolution, or the upgrader chain |
| AggregateId validation | The wrong-identity stream is rejected | Repair routing or historical identity; do not bypass validation |
| Next-version validation | `SourcingVersionConflictException` is thrown | Find a missing, duplicate, or out-of-order event stream |
| State sourcing function | This restoration attempt fails | Repair event compatibility or deterministic state logic, then reload into a new object |

`onSourcing` updates aggregate metadata before invoking business sourcing functions, so do not reuse that in-memory object after a function throws; create or load a fresh object through the repository. Whether event append succeeded, message sending, snapshots, projections, and Saga failures belong to command and event-processing stages; `StateAggregate` itself neither commits nor rolls them back.

## Boundary With the Command Processing Pipeline

This page ends at `StateAggregate` creation, restoration, and sourcing. When `CommandAggregate` invokes state sourcing, when EventStore appends, how Gateway/Dispatcher/Filter components propagate errors, and when wait stages complete all belong to the [Command Processing Pipeline](../command/internals/pipeline.md).

The domain boundary requires only that the command side decide facts from current state, the state side evolve only from events that happened, and in-memory state before event-history persistence is not authoritative. Do not duplicate the complete command pipeline in the domain lifecycle page.

## Source and Verification Entry Points

Core implementations: [`StateAggregate`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/state/StateAggregate.kt), [`SimpleStateAggregate`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/state/SimpleStateAggregate.kt), and [`EventSourcingStateAggregateRepository`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/EventSourcingStateAggregateRepository.kt).

The narrow repository verification entry points are:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.modeling.state.SimpleStateAggregateSourcingTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.modeling.state.SimpleStateAggregateDeletionRecoveryTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.eventsourcing.EventStoreStateAggregateRepositoryTest"
```

Also run the corresponding contract tests and full historical replay for the actual EventStore, SnapshotStore, and event samples. Core unit tests do not prove production storage or data compatibility.
