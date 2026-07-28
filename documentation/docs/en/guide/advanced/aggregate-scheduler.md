---
title: Aggregate Scheduler
description: Dedicated Reactor Scheduler for each aggregate to control concurrent execution and resource allocation.
---

# Aggregate Scheduler

The aggregate scheduler provides a dedicated Reactor Scheduler for each aggregate, used to control concurrent execution and resource allocation.

## Scheduler Supplier

The aggregate scheduler supplier provides or creates a dedicated scheduler for each aggregate.
It extends `GracefullyStoppable` so the runtime can dispose every cached scheduler during
shutdown.

```kotlin
interface AggregateSchedulerSupplier : GracefullyStoppable {
    fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler
    // inherited: stopGracefully(): Mono<Void>
}
```

### Default Implementation

`DefaultAggregateSchedulerSupplier` lazily creates one `Schedulers.newParallel` per
materialized aggregate and caches it. The constructor accepts a `name` (used as the
scheduler-name prefix) and an optional `parallelism` (default
`Schedulers.DEFAULT_POOL_SIZE`); it also implements `ParallelismCapable` and `Named`.

```kotlin
class DefaultAggregateSchedulerSupplier(
    override val name: String,
    override val parallelism: Int = Schedulers.DEFAULT_POOL_SIZE
) : AggregateSchedulerSupplier,
    ParallelismCapable,
    Named {

    private val schedulers: MutableMap<MaterializedNamedAggregate, Scheduler> = ConcurrentHashMap()

    override fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler =
        schedulers.computeIfAbsent(namedAggregate.materialize()) { _ ->
            Schedulers.newParallel("$name-${namedAggregate.aggregateName}", parallelism)
        }

    override fun stopGracefully(): Mono<Void> {
        // disposes every cached scheduler during graceful shutdown
    }
}
```

The first call for a named aggregate creates a parallel scheduler named
`{supplier-name}-{aggregateName}` (for example `order-service-order`); subsequent calls
for the same aggregate return the cached instance.

## How Dispatchers Use the Scheduler

Every Wow dispatcher (command, domain-event, state-event, projection, saga, snapshot)
obtains its per-aggregate scheduler from the supplier and uses `publishOn(scheduler)` to
guarantee that all messages for one aggregate instance are processed on the same scheduler
(in turn, serially per aggregate). The wiring is centralized in the per-aggregate
dispatcher factory:

```kotlin
// EventStreamDispatcher — one dispatcher is created per NamedAggregate
override fun newAggregateDispatcher(namedAggregate: NamedAggregate): AggregateEventDispatcher {
    return AggregateEventDispatcher(
        namedAggregate = namedAggregate,
        messageFlux = ...,
        scheduler = schedulerSupplier.getOrInitialize(namedAggregate), // dedicated scheduler
        // ...
    )
}
```

Inside `AbstractAggregateEventDispatcher`, the grouped flux is published onto that scheduler:

```kotlin
messageFlux
    .groupBy { it.toGroupKey(parallelism) }   // spread across parallelism lanes
    .flatMap { grouped -> grouped.publishOn(scheduler) ... } // same aggregate -> same scheduler
```

This is the foundation of Wow's **serial-per-aggregate** processing guarantee: because one
aggregate always maps to one cached scheduler, two commands for the same aggregate cannot
run on different threads simultaneously, while different aggregates run in parallel.

## Why a Dedicated Scheduler Per Aggregate?

| Concern | How the per-aggregate scheduler addresses it |
|---|---|
| **Ordering** | Events for one aggregate type share a scheduler. Within that scheduler, `AggregateDispatcher` hashes aggregate IDs into `parallelism` lanes; events in the **same lane** are serialized via `concatMap`, but events for **different aggregate IDs** may be processed concurrently across lanes. Order is guaranteed per aggregate instance, not across instances. |
| **Isolation** | Different aggregate types (e.g. `order` vs `cart`) get separate schedulers, so a slow one does not block another. |
| **Backpressure** | Each named aggregate's scheduler has its own queue; contention is bounded per aggregate type, not global. |
| **Resource control** | `parallelism` caps the worker count per named aggregate type, preventing one hot type from consuming all CPU. |
| **Graceful shutdown** | `stopGracefully()` disposes every cached scheduler during application shutdown. |