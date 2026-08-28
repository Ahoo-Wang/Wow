---
title: Domain Model
description: Enter Wow's aggregates, event history, and snapshots through first modeling, historical evolution, or restoration performance.
outline: deep
---

# Domain Model

Wow uses an aggregate as a consistency boundary: the command side decides from current state, the state side evolves only through domain events, event streams in EventStore preserve authoritative history, and snapshots only accelerate latest-state restoration.

This section covers domain boundaries, state evolution, historical compatibility, and restoration cost. Complete Gateway, Dispatcher, Filter, and wait-stage behavior belongs to `command/internals/pipeline`, not to the domain model.

## Choose a Reading Path

### First Modeling

1. Read [Aggregate and Invariants](./aggregate.md) to define aggregate identity, business invariants, and allowed state transitions.
2. Read [Event Sourcing](./event-sourcing.md) to establish that event streams are authoritative history and state evolution is deterministic.
3. Read [Aggregate Lifecycle](./lifecycle.md) to confirm creation, restoration, deletion, and concurrency boundaries.

**Completion signal:** Every business intent has an explicit success event or rejection result, and the same initial state plus event order produces the same state.

**Primary next step:** [Define Commands](../command/definition.md) to map each business intent to its target aggregate and handling function.

### Historical Evolution

1. Start with [Event Sourcing](./event-sourcing.md) to confirm current streams, revision distribution, and restoration boundaries.
2. Use [Event Evolution](./event-evolution.md) to design single-step upgraders, chain order, and field compatibility.
3. Use [Aggregate Lifecycle](./lifecycle.md) to verify versions, metadata, and business state after replaying old events.

**Completion signal:** Every historical revision that still exists upgrades and replays into current state, with critical business invariants and downstream outcomes verified.

**Primary next step:** Run a full replay with sanitized real-history samples and keep a repeatable verification entry point.

### Restoration Performance

1. Use [Event Sourcing](./event-sourcing.md) to measure the number and duration of events replayed for latest-state restoration.
2. Read [Snapshots](./snapshot.md) and select a strategy and store only when performance evidence exists.
3. Use [Aggregate Lifecycle](./lifecycle.md) to ensure latest restoration and point-in-time restoration do not mix snapshot rules.

**Completion signal:** The selected strategy meets a measurable restoration target, and missing or stale snapshots can still be rebuilt from authoritative event history.

**Primary next step:** Benchmark restoration against realistic aggregate histories and verify the selected SnapshotStore's monotonic-save contract.

## Page Responsibilities

| Page | Question answered |
| --- | --- |
| [Aggregate and Invariants](./aggregate.md) | How are consistency boundaries, identity, and business rules modeled? |
| [Event Sourcing](./event-sourcing.md) | Which history is authoritative, and how is state restored deterministically? |
| [Snapshots](./snapshot.md) | When should a replaceable checkpoint optimize latest-state restoration? |
| [Event Evolution](./event-evolution.md) | How can persisted event-schema changes remain compatible with old history? |
| [Aggregate Lifecycle](./lifecycle.md) | How is a StateAggregate created, restored, and evolved in order? |
