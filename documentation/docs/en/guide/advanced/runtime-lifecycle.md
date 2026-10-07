---
title: Runtime Lifecycle
description: WowRuntime readiness, activity admission, graceful shutdown, force cleanup, and Spring ownership.
outline: deep
---

# Runtime Lifecycle

`WowRuntime` is the common owner of all `RuntimeComponent` instances. It decides when the whole runtime may accept work, when admission closes, and how work drains under one deadline. It does not decide whether an individual business message should be retried.

## Component contract

```kotlin
interface RuntimeComponent {
    fun prepare(runtimeContext: RuntimeContext): Mono<Void>
    fun start()
    fun quiesce() = Unit
    fun stopGracefully(): Mono<Void>
    fun forceStop()
}
```

| Method | Contract |
| --- | --- |
| `prepare` | Acquire subscriptions or resources and complete when new work can be retained; processing remains closed |
| `start` | Open processing after every component has prepared |
| `quiesce` | Promptly, non-blockingly, and idempotently close component intake after global admission closes |
| `stopGracefully` | Drain accepted work and asynchronously release resources |
| `forceStop` | Be prompt, non-blocking, repeatable, and safe before prepare |

Component construction must remain inert. `RuntimeComponent` does not extend `AutoCloseable`, so a container must not infer a second cleanup owner.

## Startup: all ready before processing opens

```mermaid
sequenceDiagram
    participant Owner as WowRuntime
    participant Group as RuntimeComponentGroup
    participant C1 as Component 1
    participant C2 as Component 2

    Owner->>Group: prepare(runtimeContext)
    Group->>C1: prepare
    Group->>C2: prepare
    Note over Owner,C2: Both prepares completed
    Owner->>Group: start()
    Group->>C1: start
    Group->>C2: start
    Owner-->>Owner: RUNNING
```

Preparation and startup follow registration order. If any preparation fails, the runtime enters startup rollback and cleans up lifecycle-entered components in reverse order. `start()` returns a cold `Mono`; cancelling its subscription aborts startup and force-stops this one-shot runtime.

The barrier proves only the readiness reported by components. A custom transport must keep `prepare` incomplete until new messages cannot be missed in the startup window, not merely until a client object exists.

## Activity admission

`RuntimeContext.tryAcquire()` requests a `RuntimeActivity` for one complete asynchronous operation:

```text
candidate work arrives
  → tryAcquire()
  → null: reject admission and do not acknowledge the message as handled
  → lease: run the complete asynchronous chain and close on termination
```

Closing a lease is idempotent. The lease must cover the full asynchronous chain, not just enqueueing into a local buffer, or quiescence may observe a false idle state. A terminal component-pipeline failure is reported with `reportFailure(error)`; ordinary business failures remain owned by their filter, compensation, and acknowledgement policies.

## Graceful shutdown

```mermaid
flowchart LR
    Stop[Shutdown requested] --> Quiet[Observe continuous quiet period]
    Quiet --> Close[Atomically close global admission]
    Close --> Quiesce[Quiesce in registration order]
    Quiesce --> Drain[stopGracefully in reverse order]
    Drain --> Done[Publish termination]
    Stop -. global deadline .-> Force[forceStop in reverse order]
```

Each new runtime activity restarts the quiet period. After a continuous idle interval reaches `shutdownQuietPeriod`, the runtime closes global admission before component intake. Tail work can therefore acquire a lease during handoff gaps where upstream publication has completed but downstream consumption is only beginning.

`shutdownTimeout` bounds the entire shutdown from creation of the shutdown owner, not one component. Deadline expiry records a `TimeoutException` and transfers ownership to force cleanup. `stop(timeout)` limits only that caller's blocking wait; it does not replace the runtime deadline.

## Failures and races

| Scenario | Current implementation behavior |
| --- | --- |
| `prepare` / `start` fails | Preserve the startup error and roll back entered components in reverse order |
| A component reports a fatal error | Close global admission immediately, skip the normal quiet period, drain admitted work, and terminate the whole runtime |
| Graceful cleanup fails | Continue best-effort cleanup; the first failure remains primary and later failures are suppressed |
| Deadline expires | Cancel the graceful owner and force-stop all components |
| Force overlaps a lifecycle action | Invoke compensating `forceStop` again after the method returns or publisher terminates when required |

`forceStop` must therefore tolerate repeated calls in partially initialized states. Once terminal failure is published, it is sealed; late cleanup errors do not mutate the already published result.

## Component order and resource ownership

`RuntimeComponentGroup` requires distinct component identities in one group and uses these orders:

- `prepare`, `start`, and `quiesce`: registration order;
- `stopGracefully` and `forceStop`: reverse registration order;
- once force wins, a detached graceful chain cannot advance into another component.

Dispatchers own no threads: they run on the runtime's [`KeyedExecutor`](./keyed-executor.md), which the runtime closes after every component has stopped.

A force stop also stops the dispatch work synchronously, as disposing the 9.2 per-aggregate schedulers did: before `forceStop` returns, the runtime force-closes the executor, which discards every message still queued in a mailbox and rejects new deliveries. Those messages are not acknowledged, so a broker transport (Kafka, Redis Streams) redelivers them; an in-memory message is dropped. A handler that is already running is not interrupted, but its mailbox starts no further message. A graceful stop is unchanged: dispatchers drain first and the executor is closed only after every component has stopped; a task submitted while the executor closes runs or is rejected, never left behind.

## Storage and transport resources

A batch writer (the Mongo and Elasticsearch event-stream appender and snapshot saver with batching enabled) and a Kafka producer hold work the dispatchers already accepted. Each is a `RuntimeResource`:

```kotlin
interface RuntimeResource {
    fun stopGracefully(): Mono<Void>
    fun forceStop()
}
```

The `RuntimeResources` component owns them. Registered first, it stops last: after every dispatcher has drained, it flushes all resources concurrently, inside the same `shutdownTimeout`. If the deadline expires, `forceStop` fails the unwritten items. A batch writer's force stop ends its graceful stop. Kafka's client cannot cancel a running close, so the runtime stops waiting for it. The stores and buses stay `AutoCloseable`. After a flush, their `close()` returns at once; a batch writer's `close()` after a force stop also returns at once, without an error, however often Spring calls it.

A force stop bounds how long the runtime waits, not what is still running:
- **Kafka:** the abandoned close keeps flushing in the background. When Spring destroys the bus, its `close()` can block until that flush ends.
- **Batch writers:** a write that had already started is cancelled and its items fail, but the store may already have received it. The JVM does not wait for it either, since the lifecycle threads are daemon threads: a process that exits mid-flush can leave such a write applied or not.

Size `wow.shutdown-timeout` so that a normal flush fits inside it.

## Lifecycle threads

The runtime owns its lifecycle threads in one place, `RuntimeExecutionResources`:
- One bounded, daemon lifecycle pool. Shutdown, public terminal observers, termination control and physical cleanup each run on their own lane.
- One deadline timer thread.

The pool reserves each lane's threads. An observer that blocks therefore holds only the observer lane, and cannot delay shutdown, the termination control plane or cleanup. Idle threads time out.

## Spring ownership

The Starter supplies the single `WowRuntimeLifecycle` adapter to Spring `SmartLifecycle`. The default runtime collects singleton `RuntimeComponent` beans from the current application context, orders them with Spring semantics, and rejects competing Spring lifecycle, destroy-method, or cleanup owners.

The default runtime also registers `RuntimeResources` first, resolving every `RuntimeResource` bean when the runtime prepares. The Mongo, Elasticsearch and Kafka auto-configurations register one for each store and bus they create.

An application-provided runtime explicitly owns its component topology; the Starter does not append auto-discovered components. Such a runtime should put a `RuntimeResources` first, or its batch writers are flushed only when Spring closes the stores. [Spring Boot Starter](../extensions/spring-boot-starter.md#bean-wiring-and-overrides) owns bean names, configuration, and replacement rules.

## Custom component checklist

1. Do not open subscriptions or background threads in the constructor.
2. Complete `prepare` at real readiness while processing remains closed.
3. Hold one lease for every admitted asynchronous operation until full termination.
4. Close logical intake synchronously in `quiesce`; do not block for a long operation.
5. Make `forceStop` safe before prepare, idempotent, and non-blocking.
6. Use `reportFailure` only for terminal pipeline failure.
7. Test force races with prepare, start, quiesce, and graceful stop.

## Verification and operations

Defaults and constraints live in the [Core Configuration Reference](../../reference/config/core.md). Narrow implementation evidence is available with:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.runtime.WowRuntimeTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.runtime.internal.RuntimeComponentGroupTest"
```

Module tests verify the implementation contract only. Production quiet-period and timeout values still require evidence from real handoff jitter, maximum drain time, and resource cleanup time.

## Source and related pages

- [`WowRuntime`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/runtime/WowRuntime.kt)
- [`RuntimeComponent`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/runtime/RuntimeComponent.kt)
- [`RuntimeContext`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/runtime/RuntimeContext.kt)
- [`RuntimeComponentGroup`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/runtime/internal/RuntimeComponentGroup.kt)
- [Runtime Orchestration Migration](../migration/runtime-orchestration.md): breaking lifecycle migration boundary
- [Keyed Executor](./keyed-executor.md): the runtime-owned dispatch workers
