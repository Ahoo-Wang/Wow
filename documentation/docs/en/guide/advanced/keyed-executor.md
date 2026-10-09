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

- **One receiver per bounded context.** Each dispatcher opens one receiver per bounded context of its aggregates, subscribed to all of that context's aggregate topics, in the same consumer group as before (see [Kafka consumer groups](../extensions/kafka.md#consumer-groups)).
- **One worker set per runtime.** The thread count depends on the hardware, not on the number of aggregate types or dispatchers. 20 aggregate types on 16 cores use 16 dispatch threads, not 20 × 16 per dispatcher kind. A worker thread starts when it is first given work, so a service that only sends commands (no dispatcher receives anything) starts none.
- **One mailbox per aggregate ID.** Messages of one aggregate ID run one at a time, in the order the dispatcher received them. Messages of different aggregate IDs run in parallel. The mailbox key is the bounded context, aggregate name and ID; the tenant is not part of it, as in 9.2 (which grouped by the ID), so messages of one aggregate ID run one at a time even when they carry different tenants. A mailbox exists only while it has messages, and runs on the worker it was given when it was created (there is no work stealing: the affinity is fixed for the mailbox's lifetime, so a mailbox queued behind a long synchronous turn waits for it even if another worker is idle): a running worker with nothing queued if there is one (a short wait behind it is cheaper than waking a parked worker), else a parked one. Each worker has its own lock-free FIFO queue; a busy worker takes the next mailbox without being woken.
- **Optional: a worker can spin briefly before it parks.** By default (`spin` = `0`) a worker that runs out of mailboxes parks at once. With `spin` set, it polls its queue for up to that long before it parks, but only when its last wait was shorter than that; after a longer wait it parks at once. A spinning worker takes the next mailbox without the wake-up a park costs: the sender's OS call to unpark it and the scheduling delay before it runs again, which on small cloud machines is tens of microseconds per message. It pays only for in-memory, high-rate dispatch whose messages keep arriving within microseconds of each other: on 4-vCPU CI runners the closed-loop event-dispatch benchmark (one producer, eight processors) was 0 to 6% faster with `20us` and 16 to 63% faster with `100us` than with `0`, depending on the CPU model. With a real store it does not: with MongoDB and Redis the command-write benchmarks gained 0 to 3% over no spin, within noise. The cost is CPU: while messages keep arriving at intervals shorter than `spin`, a worker can stay busy on one core the whole time, and after each short interval the next wait can spend up to `spin` of CPU even when no message follows. In the worst case that is `workers` cores. Keep it at `0` in a container with a CPU quota (cgroup limit). Closing or force-stopping the executor ends a spin immediately.
- **Fair turns.** A mailbox runs at most `throughput` messages in one turn while they complete synchronously, then goes behind the other mailboxes of its worker, so a hot aggregate cannot starve the others.
- **Waiting holds no worker.** A handler that waits — non-blocking I/O, or the command retry backoff after a version conflict — releases its worker and delays only its own mailbox. In 9.2 a conflicting aggregate blocked every aggregate hashed to the same group for up to the whole backoff.
- **Bounded in-flight messages.** Each receiver of a dispatcher holds at most `max-in-flight` messages it has not finished (running or queued in a mailbox). It requests more from its transport as messages finish, in small batches (1/16 of the window), so a few slow messages do not stop the flow. A slow dispatcher therefore backpressures the transport (Kafka pauses fetching, a local-first sender waits for local admission) instead of buffering without limit.
- **The window is shared by all aggregate IDs of a receiver.** A receiver serves one bounded context of one dispatcher, so the window is shared by every aggregate type of that context. If one aggregate ID accumulates a backlog of about `max-in-flight − max-in-flight / 16` unfinished messages (241 by default) — a hot aggregate behind a slow store, or a handler that does not complete — the receiver requests nothing more: the other aggregate IDs of that context wait too, and on Kafka all of the context's topics pause for that dispatcher. Other receivers and dispatchers are not affected. Watch per-aggregate backlogs, and raise `max-in-flight` if one aggregate legitimately runs far ahead of the others.
- **A failing handler does not take a worker down.** A handler error fails its dispatcher, which reports it to the runtime (the runtime then stops). That includes JVM-fatal errors such as `LinkageError`, `NoClassDefFoundError` or `StackOverflowError`: such an error is logged at ERROR and reaches the runtime wrapped in an `IllegalStateException` (its cause), so the runtime stops as for any handler failure (under Spring the application context closes), and the worker thread contains the error and keeps running the other mailboxes pinned to it, as the 9.2 `newParallel` threads survived a failing task. This is a change from 9.2, where a fatal error thrown by a handler silently wedged its dispatch group while the runtime kept running; 9.3 fails fast, as it does for any other handler error. A fatal error raised on another thread, for example in a driver's callback, never reaches the mailbox: the handler's publisher does not complete, and that mailbox stays wedged, as in 9.2.
- **Coroutines resume on the workers.** A `suspend` or `Flow` message function called by a dispatcher runs its coroutine on the executor's workers instead of `Dispatchers.Default`. The mailbox does not start the aggregate's next message before the function returns, so per-aggregate serialization holds across suspension points. Called outside a dispatcher (for example from a test), such a function still runs on `Dispatchers.Default`.

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `wow.dispatch.workers` | available processors | Worker threads shared by all dispatchers of the runtime |
| `wow.dispatch.max-in-flight` | `256` | Unfinished messages one receiver (a dispatcher's receiver of one bounded context) holds before it stops requesting more |
| `wow.dispatch.throughput` | `16` | Messages of one aggregate a worker runs in one turn before moving to other aggregates |
| `wow.dispatch.spin` | `0` | How long a worker that ran out of messages spins before it parks, when its last wait was shorter; `0` (the default) never spins, at most `1ms`. Opt-in for in-memory, high-rate dispatch; within noise with a real store. Give a unit suffix: a bare number is read as milliseconds (and `20` would be rejected by the 1 ms limit). Costs up to one core per worker while messages arrive faster than this |

The 9.2 system property `wow.parallelism` is ignored; a runtime started with it set logs one WARN pointing to `wow.dispatch.*`.

With Spring the executor is a `KeyedExecutor` bean that the context closes, so it is released even when the context fails to refresh; declare your own `KeyedExecutor` bean to replace it.

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
