---
title: Troubleshooting
description: Diagnose command, storage, snapshot, projection, and saga failures from the last completed Wow stage, wiring conditions, and backend evidence.
outline: deep
---

# Troubleshooting

Answer two questions first: **what was the last completed stage, and which bus, store, or handler owns the next one?** Do not begin by increasing a timeout or switching backends.

::: warning A timeout is not failure evidence
A caller timeout means only that the target signal did not arrive before its deadline. The command may be pending, still processing, or complete without a delivered notification. Do not retry under a new `requestId` before querying authoritative state.
:::

## Collect a Minimal Evidence Bundle First

Before changing code or configuration, retain the same time window of:

- application build identity, JDK, Wow BOM/dependency lock, and capabilities on `runtimeClasspath`;
- complete exception chain and Spring condition evaluation report;
- `requestId`, `commandId`, `contextName`, `aggregateName`, `aggregateId`, and known version;
- requested `CommandStage`, function target, and last `CommandResult.stage`;
- redacted effective `wow.*` and relevant `spring.*` configuration plus storage routes;
- broker offsets/lag/pending entries, EventStore/SnapshotStore health, traces, and metrics.

Enable this only for a controlled reproduction window:

```yaml
logging:
  level:
    me.ahoo.wow: DEBUG
```

Debug logs may contain business IDs, headers, and error context. Redact them before a public issue and never leave this enabled indefinitely in production.

## Symptom-to-Stage Quick Reference

| Last evidence | Next owner | Inspect first |
| --- | --- | --- |
| Application did not start | Capability / auto-configuration | Dependency variant, `*.enabled`, required connection, missing/duplicate bean |
| No `SENT` | CommandBus | Send error, topic/Stream, ACL, serialization, network |
| Only `SENT` | Command Dispatcher / Aggregate | Metadata, handler, aggregate load, business error |
| Missing `PROCESSED` | EventStore / DomainEventBus | Append, version conflict, request ID, broker send |
| Only `PROCESSED` | StateEvent/Snapshot or target function | StateEvent lag, SnapshotStore, function identity, consumer lag |
| `SNAPSHOT` arrived but query is stale | Snapshot strategy / query binding | `version_offset` skip, query routed to the same backend |
| Projection/effect runs twice | Handler | Idempotency key, ACK/offset, redelivery, compensation record |
| Redis pending entries grow | Redis bus recovery | Group, idle time, claim failure, Stream trimming |
| Kafka receiver repeatedly fails | Decode/receiver policy | First invalid record, `decode-failure-strategy`, backoff, offset |
| Shutdown times out | WowRuntime component owner | Ingress removal, active work, non-cancellable I/O, batch drain |

## Command Timeouts

### 1. Confirm the requested stage

| Stage | Trace when missing |
| --- | --- |
| `SENT` | Gateway → selected CommandBus send |
| `PROCESSED` | Command Dispatcher → aggregate load/process → EventStore append → DomainEventBus send |
| `SNAPSHOT` | StateEventBus → Snapshot Dispatcher → SnapshotStrategy/Store |
| `PROJECTED` | Target projection function, last-projection signal, read-model write |
| `EVENT_HANDLED` | Target event handler, external dependency, retry/compensation |
| `SAGA_HANDLED` | Target saga and derived-command send; do not infer downstream aggregate completion |

For function stages, verify `contextName`, `processorName`, and `functionName` together. Success from another function cannot satisfy the wrong target.

### 2. Trace one identity through the pipeline

An HTTP client supplies a stable `requestId` in `Command-Request-Id`. With a response, `CommandResult` exposes the server `commandId`. Without one, locate the command ID in logs/spans by request ID, then correlate by AggregateId and stage. Do not depend on fixed English log sentences; identities and stages are more stable.

### 3. Do not hide the cause with a larger timeout

- With no `SENT`, a larger `PROJECTED` timeout cannot help.
- When requests always stop after `PROCESSED`, inspect the target consumer path instead of repeating the aggregate command.
- When only hot aggregates time out, compare replay length, version conflicts, and backend latency.
- After caller timeout, query authoritative state and preserve the original `requestId` before retrying.

`WaitPlan.withTimeout` is a caller-local deadline and is not propagated in the command header. See [Completion Semantics](./command/completion.md#timeout-cancellation-and-unknown-outcomes) for the full contract.

## Aggregate, Idempotency, and Concurrency Errors

### `DuplicateRequestIdException`

EventStore confirmed that this `requestId` already exists for the aggregate. For a retry of the same logical command, this is an idempotent result. Otherwise, repair request-ID generation or scope. A new ID bypasses this protection.

### `DuplicateAggregateIdException`

A create command attempted to initialize an existing aggregate. Check ID allocation, `isCreate` semantics, and caller retry. Do not retry it indefinitely as an ordinary network failure.

### `EventVersionConflictException`

The append expected version differs from EventStore head. Wow uses bounded backoff only for errors classified as recoverable. Persistent conflict requires inspecting a hot aggregate, stale `aggregateVersion`, or a custom bus/store that violates per-aggregate ordering—not unbounded retry.

## Missing Metadata or Handler Registration

1. Confirm the domain module applies KSP and uses `wow-compiler` in `ksp(...)`.
2. Confirm the service `runtimeClasspath` contains the domain module, not only its API module.
3. Clean/build the target module and inspect `META-INF/wow-metadata.json` in the artifact.
4. Verify `spring.application.name` / `wow.context-name`, aggregate name, and function metadata.
5. If an HTTP route alone is missing, verify `webflux-support`; do not add a duplicate controller to mask a metadata problem.

## Projection Lag or Duplicate Side Effects

### Separate backlog from slow single-message processing

- Lag/pending grows continuously: locate partition/consumer group, persistent failure, and downstream capacity.
- Lag is stable but one execution is slow: measure deserialization, business function, and external I/O separately.
- Processing completed but waiting did not: verify function target and `isLastProjection` before scaling.

Use `@Blocking` or a bounded scheduler only for an unavoidable blocking API. It cannot improve a slow query or unbounded queue.

### Handlers must be retry-safe

Use a business unique key, event ID, or target version for idempotency. Read the current external-effect state before retrying. After automatic retries are exhausted, preserve the compensation record and original error and compensate the target function. Do not “remove” lag by acknowledging and discarding work.

## Slow Aggregate Loads or Snapshot Problems

1. Record EventStore head, Snapshot version, replayed stream count, and sourcing duration.
2. Measure SnapshotStore load, EventStore load, and sourcing functions separately.
3. If Snapshot differs from full replay, stop relying on that read path and use aggregate specs to locate non-deterministic sourcing.
4. If `SNAPSHOT` completed without a write, verify whether `version_offset` skipped below-threshold work.
5. If queries are stale, prove storage routing maps SnapshotStore and SnapshotQueryServiceFactory to the same binding.

## Connectivity and Auto-Configuration

### Isolate framework behavior from an external backend

Use a fully in-memory minimal reproduction and explicitly disable integrations that may remain on the classpath:

```yaml
wow:
  kafka.enabled: false
  mongo.enabled: false
  redis.enabled: false
  elasticsearch.enabled: false
  prepare.enabled: false
  command.bus.type: in_memory
  event.bus.type: in_memory
  eventsourcing.store.storage: in_memory
  eventsourcing.snapshot.storage: in_memory
  eventsourcing.state.bus.type: in_memory
```

A passing in-memory path narrows the failure to an external adapter. It is not a production fallback and does not prove real backend semantics.

### Bean assembly failures

Check in this order:

1. The matching capability is present on `runtimeClasspath`.
2. `wow.*.enabled`, bus/storage selection, and Spring Boot connection properties agree.
3. The first failed `@Conditional*` in the condition report.
4. A storage route sets exactly one of `storage`/`binding`, with both store and query-factory bindings present.
5. A custom bean did not create multiple candidates or replace auto-configuration unexpectedly.

See [Core Configuration](../reference/config/core.md) and [Infrastructure Configuration](../reference/config/infrastructure.md) for properties and defaults.

## Performance and Alerting

Thresholds come from application SLOs and target-hardware baselines; there is no universal one-second command target. Decompose latency by stage and correlate aggregate replay count, version conflict, EventStore/SnapshotStore latency, broker lag/pending, handler retry/compensation, and shutdown drain. Without production-like data volume, label the conclusion `MISSING EVIDENCE`.

Framework JMH establishes a framework baseline only; it does not replace application query plans or end-to-end load. See [Framework Tests and Benchmarks](./test-runtime.md#benchmarks-have-three-uses).

## File a Diagnosable Issue

Search [GitHub Issues](https://github.com/Ahoo-Wang/Wow/issues) by complete exception class and `errorCode`. Include a minimal failing test, complete exception chain, last stage, redacted configuration, relevant capabilities, and backend health/lag evidence. Remove passwords, tokens, certificates, real URI credentials, and sensitive business payloads.
