---
title: Troubleshooting
description: Locate Wow failures by command, aggregate, event-store, projection, and saga stage, then collect reproducible evidence.
outline: deep
---

# Troubleshooting

Troubleshooting should answer two questions before increasing a timeout: **what was the last completed stage, and why did the next stage not complete?**

::: warning A timeout is not proof that a command failed
A caller timeout only means the target signal was not observed before its deadline. The command may be unprocessed, still processing, or complete with a result signal that did not arrive. Do not retry with a new `requestId` until you establish the current state.
:::

## Collect a Minimal Evidence Bundle First

Before changing configuration or code, preserve:

- Wow version, JDK version, and enabled `wow-*` modules.
- The complete exception chain, not only its last line.
- `commandId`, `requestId`, `contextName`, `aggregateName`, `aggregateId`, and any known aggregate version.
- The requested `CommandStage` and last observed `CommandResult.stage`.
- Effective `wow.*` and backend `spring.*` configuration, with passwords, tokens, and URI credentials redacted.
- Logs, spans, metrics, consumer lag, and backend health from the same time window.

Raise Wow logging only in a reproducible environment and for a bounded time window:

```yaml
logging:
  level:
    me.ahoo.wow: DEBUG
```

Debug logs may contain business identifiers and message context. Do not enable them indefinitely in production or attach unredacted logs to a public issue.

## Symptom-to-Stage Quick Reference

| Symptom | Confirm first | Most common next step |
| --- | --- | --- |
| A command returns no result | Did it reach `SENT`? | Check CommandBus connectivity, routing, and send errors |
| It reaches only `SENT` | Is aggregate metadata and its handler registered? | Check KSP output, bounded context, and aggregate loading |
| It reaches only `PROCESSED` | Is the target `SNAPSHOT`, `PROJECTED`, or `SAGA_HANDLED`? | Inspect that processor, target function, and consumer lag |
| HTTP write succeeds but an immediate query is stale | Does the write wait for the relevant projection? | Use a precise `PROJECTED` target instead of fixed sleep |
| A side effect runs more than once for one event | Is the handler idempotent? | Fix side-effect idempotency before investigating redelivery or ACK behavior |
| Aggregate loading is slow | How many events replayed, and was a snapshot used? | Inspect snapshot policy and EventStore query latency |
| Startup misses a bean or aggregate | Are dependencies, capability, and auto-configuration conditions present? | Open the Spring condition report and verify property keys |
| Shutdown hangs | Which runtime component still owns active work? | Follow [Runtime Lifecycle](./advanced/runtime-lifecycle.md) ownership and timeout checks |

## Command Timeouts

### 1. Confirm the requested stage

| Stage | Meaning | If it is missing, inspect |
| --- | --- | --- |
| `SENT` | The command bus accepted the command | CommandBus implementation, network, serialization, and routing |
| `PROCESSED` | Aggregate decision and event append completed | Handler registration, aggregate load, business errors, and EventStore |
| `SNAPSHOT` | Snapshot processing completed | State-event bus, snapshot processor, and SnapshotStore |
| `PROJECTED` | A specific projection function completed | Target matching, projection errors, consumer lag, and read storage |
| `EVENT_HANDLED` | A specific event processor completed | Handler filters, retry, compensation, and external dependencies |
| `SAGA_HANDLED` | A specific saga function handled the source event | Saga matching, handler errors, and derived-command send; it does not mean the downstream command completed |

For function-scoped stages, also verify `contextName`, `processorName`, and `functionName`. Completion of another projection or saga does not satisfy a mismatched target.

### 2. Trace one identity through the pipeline

Use the client-supplied `requestId` from `Command-Request-Id` to correlate an HTTP request. A `CommandResult` also exposes the server-generated `commandId`; if no result was returned, recover that command ID from server logs or spans correlated by `requestId`. Do not rely on fixed English log text: message wording may evolve, while identifiers and stages are the stable evidence.

### 3. Do not hide the cause with a larger timeout

- If `SENT` is missing, increasing a `PROJECTED` timeout cannot help.
- If requests consistently stop after `PROCESSED`, inspect the target projection or saga and its consumer lag.
- If only a small fraction time out, compare aggregate hot spots, replay length, backend latency, and retries.

See [Command Gateway](./command-gateway.md#wait-plans) for the complete wait contract.

## Aggregate, Idempotency, and Concurrency Errors

### `DuplicateRequestIdException`

The same `requestId` was used for the same aggregate. Determine whether this is a retry of one logical request or accidental identifier reuse. Retrying the same logical request should preserve its original `requestId`; replacing it bypasses idempotency protection.

### `DuplicateAggregateIdException`

A create command attempted to initialize an aggregate that already exists. Check ID generation, create semantics, and client retries. Do not treat it as an ordinary version conflict to retry indefinitely.

### `EventVersionConflictException`

The expected event-stream version differs from the stored version. Wow performs bounded retries for recoverable errors, but persistent conflicts usually indicate a hot aggregate or a business boundary that needs attention. Measure conflict frequency and affected aggregate identities; unlimited retry is not a fix.

## Missing Metadata or Handler Registration

Typical symptoms say the runtime cannot find a context, aggregate, or handler.

1. Confirm the domain module applies KSP and depends on `wow-compiler`.
2. Confirm the aggregate and handlers follow the [modeling conventions](./modeling.md#conventions).
3. Clean and rebuild the affected module, then inspect generated `META-INF/wow-metadata.json`.
4. Confirm the host service actually depends on that domain module.

```kotlin
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    ksp("me.ahoo.wow:wow-compiler")
}
```

## Projection Lag or Duplicate Side Effects

### Separate backlog from slow single-message processing

- **Growing backlog**: inspect consumer concurrency, partitions, instances, persistent failures, and downstream capacity.
- **Slow individual processing**: use spans or processing-time distributions to locate external I/O, large serialization, or read-store writes.
- **Wait timeout only**: verify the `PROJECTED` target function. Processing may have completed without matching the wait target.

Prefer reactive clients. Use `@Blocking` only to isolate an unavoidable blocking API; it cannot repair a slow query or insufficient downstream capacity.

### Handlers must be retry-safe

Do not assume distributed delivery is exactly once. Use a business key, event ID, or target version for idempotent writes, and check an external side effect's state before retrying it. Route failures that cannot recover automatically through [Event Compensation](./event-compensation.md).

## Slow Aggregate Loads or Snapshot Problems

1. Record the aggregate version, snapshot version, and number of replayed events.
2. Measure SnapshotStore loading, EventStore loading, and sourcing-handler execution separately.
3. If a snapshot differs from full event replay, stop relying on that snapshot path and use aggregate specification tests to check every sourcing handler.
4. Validate a snapshot-strategy change against the real event distribution before changing it globally for one hot aggregate.

See [Snapshot](./snapshot.md) for configuration and semantics.

## Connectivity and Auto-Configuration

### Isolate framework behavior from an external backend

For a local minimal reproduction, temporarily use in-memory implementations:

```yaml
wow:
  kafka:
    enabled: false
  command:
    bus:
      type: in_memory
  event:
    bus:
      type: in_memory
  eventsourcing:
    store:
      storage: in_memory
    snapshot:
      storage: in_memory
    state:
      bus:
        type: in_memory
```

If the in-memory path passes and an external backend fails, continue with [Kafka](./extensions/kafka.md), [MongoDB](./extensions/mongo.md), [Redis](./extensions/redis.md), or [Elasticsearch](./extensions/elasticsearch.md). This is a diagnostic isolation technique, not a production fallback.

### Bean assembly failures

1. Check that the required module or starter capability is on the runtime classpath.
2. Open Spring Boot's condition evaluation report and identify the exact failed `@Conditional*` condition.
3. Verify prefixes and defaults against [Configuration Reference](../reference/config/core.md) instead of guessing property names.
4. Check for multiple candidate implementations or a custom bean that overrides auto-configuration.

## Performance and Alerting

There is no universal "one-second command" or "five-second projection" alert threshold. Derive thresholds from the application SLO and a baseline for the current code and hardware; distinguish p50, p95, p99, and maximum values.

At minimum, observe:

- End-to-end latency decomposed by `CommandStage`.
- Aggregate-load duration, replayed-event count, and version-conflict rate.
- EventStore and SnapshotStore read/write latency and error rate.
- Command, event, projection, and saga lag and failure rates.
- Retry, compensation, and unrecoverable-task counts.

Use the JMH tasks in [Test Runtime](./test-runtime.md#benchmark-smoke) for a reproducible baseline of the current version.

## File a Diagnosable Issue

If the cause is still unclear, search [GitHub Issues](https://github.com/Ahoo-Wang/Wow/issues) by full exception class and `errorCode`. A new issue should include:

- Wow/JDK versions and dependency modules.
- Requested stage, actual last stage, and complete exception chain.
- Relevant redacted configuration.
- A minimal reproducer or executable failing test.
- For external backends, backend version, topology, and health or lag evidence.

::: tip
A minimal test that fails reliably is more useful than a large block of debug logs without its execution context.
:::
