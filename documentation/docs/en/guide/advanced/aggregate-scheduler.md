---
title: Aggregate Scheduler
description: Cache Reactor Schedulers per named aggregate and separate thread pools, processing groups, and ordering scope.
outline: deep
---

# Aggregate Scheduler

`AggregateSchedulerSupplier` provides Reactor `Scheduler` instances to command and event dispatchers. The default implementation caches one parallel Scheduler per **materialized named aggregate**. It is not one thread per aggregate ID and is not a distributed lock.

## Supplier contract

```kotlin
interface AggregateSchedulerSupplier : GracefullyStoppable {
    fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler
    fun forceStop()
}
```

`DefaultAggregateSchedulerSupplier(name, parallelism)`:

- creates `Schedulers.newParallel("$name-${aggregateName}", parallelism)` on first access to a named aggregate;
- returns the cached instance on later access;
- defaults `parallelism` to `Schedulers.DEFAULT_POOL_SIZE`;
- rejects creation of a new Scheduler after stopping begins.

Concurrent `getOrInitialize` calls share the lifecycle monitor. Tests verify that the same key creates only one cached instance.

## Scheduler and processing groups are different

A dispatcher has its own `parallelism` for aggregate-ID group keys. Do not mix the two layers:

| Concept | Determines |
| --- | --- |
| Supplier `parallelism` | Reactor worker count for each named-aggregate Scheduler |
| Dispatcher `parallelism` | Number of logical `groupBy` groups, defaulting from `MessageParallelism.DEFAULT_PARALLELISM` |
| `toGroupKey()` | Which group receives an AggregateId |

`AggregateDispatcher` processes each group serially and allows groups to run concurrently:

```text
exchange
  → groupBy(aggregateId.id.hashCode().mod(dispatcherParallelism))
  → concatMap inside each group
  → execute groups on the named aggregate's Scheduler
```

The same aggregate ID therefore maps to the same group in one dispatcher. Different IDs may run in different groups or may collide into one serial group.

## Supported and unsupported ordering scope

The default implementation supports these statements:

- one materialized named aggregate reuses a Scheduler;
- one `aggregateId.id` maps to one group for a fixed dispatcher parallelism;
- exchanges in one group enter the handler serially.

It does not establish:

- permanent physical-thread affinity for an AggregateId;
- global order across runtime instances, broker partitions, or services;
- declaration-order execution for multiple handlers matching one event;
- replacement of EventStore version-conflict checks;
- idempotency for handler side effects.

Write consistency still depends on aggregate boundaries and EventStore append. External processing order additionally depends on bus adapters, partitions, and consumer groups.

## Ownership and shutdown

The supplier owns its cached Schedulers:

- `stopGracefully()` atomically closes creation, snapshots the cache, invokes `disposeGracefully()`, and caches the termination result for all observers;
- `forceStop()` uses the same terminal snapshot and calls immediate `dispose()`;
- force can take over an in-progress graceful disposal, and new schedulers remain rejected afterward.

`CompositeEventDispatcher` gives child dispatchers a `BorrowedAggregateSchedulerSupplier`. The borrowed view has no-op stop/force behavior, leaving the parent as the only owner that closes the real supplier.

The reverse cleanup order and global deadline come from [Runtime Lifecycle](./runtime-lifecycle.md).

## Tuning boundary

More workers or dispatcher groups increase possible concurrency, but also increase queueing, context switching, and downstream load. A single hot aggregate remains serial within one group. Before tuning, observe separately:

- queueing and processing latency per named aggregate;
- whether handlers are CPU work, non-blocking I/O, or accidental blocking calls;
- EventStore, broker, and external-system concurrency limits;
- whether Scheduler drain completes before the Runtime deadline.

Do not derive a production throughput promise from thread counts. Use the target version, hardware, parameters, and real backends for benchmark and failure evidence.

## Verification and source

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.scheduler.AggregateSchedulerSupplierTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.event.dispatcher.CompositeEventDispatcherLifecycleTest"
```

- [`AggregateSchedulerSupplier`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/scheduler/AggregateSchedulerSupplier.kt)
- [`AggregateDispatcher`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/messaging/dispatcher/AggregateDispatcher.kt)
- [`MessageParallelism`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/messaging/dispatcher/MessageParallelism.kt)
- [Event Dispatch Pipeline](../event/dispatch.md): dispatch, function concurrency, and acknowledgement
