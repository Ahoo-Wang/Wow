---
title: Keyed Executor
description: How dispatchers run messages since 9.3.0 — one CPU-sized worker set per runtime, one mailbox per aggregate ID, bounded in-flight messages, and what ordering that does and does not give.
outline: deep
---

# Keyed Executor

Since 9.3.0 every dispatcher of a `WowRuntime` — command, domain event, state event, projection, stateless saga and snapshot — runs its messages on one shared `KeyedExecutor`. It replaces the per-aggregate-type `Schedulers.newParallel(cores)` pools (`AggregateSchedulerSupplier`) and the `groupBy` into `64 × cores` serial groups (`MessageParallelism`) of 9.2.

## Model

```text
transport receiver
  → admission (runtime activity, local-first receipt)
  → mailbox of the message's aggregate ID       one per active aggregate ID, per dispatcher
  → handler on one of the runtime's workers     workers = CPU cores by default
```

- **One worker set per runtime.** The thread count depends on the hardware, not on the number of aggregate types or dispatchers. 20 aggregate types on 16 cores use 16 dispatch threads, not 20 × 16 per dispatcher kind.
- **One mailbox per aggregate ID.** Messages of one aggregate ID run one at a time, in the order the dispatcher received them. Messages of different aggregate IDs run in parallel. A mailbox exists only while it has messages, and runs on the worker it was given when it was created (there is no work stealing: the affinity is fixed for the mailbox's lifetime, so a mailbox queued behind a long synchronous turn waits for it even if another worker is idle): a running worker with nothing queued if there is one (a short wait behind it is cheaper than waking a parked worker), else a parked one. Each worker has its own lock-free FIFO queue; a busy worker takes the next mailbox without being woken.
- **Fair turns.** A mailbox runs at most `throughput` messages in one turn while they complete synchronously, then goes behind the other mailboxes of its worker, so a hot aggregate cannot starve the others.
- **Waiting holds no worker.** A handler that waits — non-blocking I/O, or the command retry backoff after a version conflict — releases its worker and delays only its own mailbox. In 9.2 a conflicting aggregate blocked every aggregate hashed to the same group for up to the whole backoff.
- **Bounded in-flight messages.** Each receiver of a dispatcher holds at most `max-in-flight` messages it has not finished (running or queued in a mailbox). It requests more from its transport as messages finish, in small batches (1/16 of the window), so a few slow messages do not stop the flow. A slow dispatcher therefore backpressures the transport (Kafka pauses fetching, a local-first sender waits for local admission) instead of buffering without limit.
- **The window is shared by all aggregate IDs of a receiver.** A receiver serves one aggregate type of one dispatcher. If one aggregate ID accumulates a backlog of about `max-in-flight − max-in-flight / 16` unfinished messages (241 by default) — a hot aggregate behind a slow store, or a handler that does not complete — the receiver requests nothing more: the other aggregate IDs of that receiver wait too, and on Kafka the receiver's topics pause. Other receivers and dispatchers are not affected. Watch per-aggregate backlogs, and raise `max-in-flight` if one aggregate legitimately runs far ahead of the others.
- **Coroutines resume on the workers.** A `suspend` or `Flow` message function called by a dispatcher runs its coroutine on the executor's workers instead of `Dispatchers.Default`. The mailbox does not start the aggregate's next message before the function returns, so per-aggregate serialization holds across suspension points. Called outside a dispatcher (for example from a test), such a function still runs on `Dispatchers.Default`.

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `wow.dispatch.workers` | available processors | Worker threads shared by all dispatchers of the runtime |
| `wow.dispatch.max-in-flight` | `256` | Unfinished messages one dispatcher holds before it stops requesting more |
| `wow.dispatch.throughput` | `16` | Messages of one aggregate a worker runs in one turn before moving to other aggregates |

Without Spring, pass the executor to the runtime, which owns it and closes it once every component has stopped:

```kotlin
val runtime = WowRuntime(
    components = listOf(commandDispatcher, eventDispatcher),
    shutdownTimeout = Duration.ofSeconds(30),
    shutdownQuietPeriod = Duration.ZERO,
    keyedExecutor = KeyedExecutor(workers = 8, maxInFlight = 256),
)
```

A dispatcher prepared with a `RuntimeContext` that is not a runtime's (as in a unit test) uses `KeyedExecutor.shared`, a process-wide executor with daemon threads.

## Ordering scope

Supported:

- messages of one aggregate ID, received by one dispatcher, are handled one at a time in receive order;
- the next message of an aggregate ID starts after the previous handler's returned `Mono` completes.

Not established:

- thread affinity for an aggregate ID (consecutive messages may run on different workers);
- order across dispatchers, runtime instances, broker partitions or services;
- declaration-order execution of several functions matching one event;
- replacement of EventStore version-conflict checks or idempotency of handler side effects.

Write consistency still comes from aggregate boundaries and the EventStore append; the order a dispatcher receives in comes from the transport (one partition per aggregate ID on Kafka).

## Tuning

Raise `workers` only for CPU-bound handlers; non-blocking I/O does not hold a worker. Raise `max-in-flight` when one dispatcher serves many concurrently active aggregates over a high-latency store; lower it to bound memory and downstream load. A single hot aggregate stays serial whatever the settings. Handlers must not block: the workers are Reactor non-blocking threads (as the 9.2 `newParallel` threads were), so `block()` on them fails fast, and a function annotated with `@Blocking` runs on `boundedElastic` instead of holding one of the few shared workers.

## Verification and source

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.execution.KeyedDispatchTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.messaging.dispatcher.AggregateDispatcherTest"
```

- [`KeyedExecutor`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/execution/KeyedExecutor.kt)
- [`AggregateDispatcher`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/messaging/dispatcher/AggregateDispatcher.kt)
- [Event Dispatch Pipeline](../event/dispatch.md): dispatch, function concurrency, and acknowledgement
- [Runtime Lifecycle](./runtime-lifecycle.md): shutdown order and deadline
