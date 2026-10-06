---
title: Migration Guide
description: Select a Wow migration path and keep source, runtime, storage, data, and production evidence separate.
---

# Migration Guide

“Migration” is not one compatibility claim. Record five independent scopes before changing anything:

| Scope | Question | Typical evidence |
|---|---|---|
| Source | Does application code compile against the pinned target API? | compiler, unit tests, generated metadata diff |
| Runtime | Does the target lifecycle/configuration start, become ready, process work, and stop correctly? | integration tests, readiness, graceful-shutdown trace/log |
| Storage | Can the target read/write the exact event, snapshot, Redis/Mongo, and BI layouts? | tag-to-tag contract diff, offline inventory, format test |
| Data | Are counts, versions, request IDs, indexes, replayed state, and read models reconciled? | manifest, checksums, representative/full reconciliation |
| Cutover | Is the approved production revision live and observable, with a rehearsed rollback? | deployment digest/revision, live traffic, alert and rollback evidence |

A green local build can close the source gate. It does not close the other four.

## Choose a Migration Path

| Current system | Primary path | Why |
|---|---|---|
| CRUD/transaction scripts/direct table writes, no Wow history | [Migrating from Traditional Architecture](./migration/traditional-architecture.md) | Establish commands, aggregates, events, import, and traffic ownership |
| Exact Wow v6 tag | [Migrate Wow v6 to v8](./migration/v6-to-v8.md) | Diff pinned platform/API/storage contracts and perform a hard data cutover where required |
| Wow v8 with custom dispatcher/message-bus/Spring lifecycle ownership | [Runtime Orchestration Migration](./migration/runtime-orchestration.md) | Move lifecycle source code to the unified `WowRuntime`; this is not automatically a data migration |
| Wow v8.16.x using old query APIs or `SnapshotRepository` | [V9 Query Migration](./query/v9-query-migration.md) | Migrate Gateway/Backend, filters, masking, SnapshotStore, and Spring bean names |
| TypeScript client on `fetcher-wow`, `fetcher-generator`, or the Wow hooks of `fetcher-react` | [Migrate from Fetcher Packages](./typescript/migration.md) | Switch to `wow-client`, `wow-generator`, and `wow-react`, then regenerate generated clients |

Do not combine first adoption and a v6→v8 upgrade into one undifferentiated release. Select a bounded context and an
exact source/target version for each change window.

## Documentation Boundaries

| Page | Owns | Does not own |
|---|---|---|
| Traditional architecture | Domain boundary, historical import, shadow catch-up, read/write cutover | Wow version/platform upgrade assumptions |
| v6→v8 | Pinned Gradle/platform matrix, source breaks, storage formats, data cutover | Redesigning every domain |
| Runtime orchestration | `RuntimeComponent`, message receiver admission, Spring lifecycle ownership, shutdown | Event/snapshot format conversion unless another section requires it |
| V9 query migration | Query Gateway/Backend, filter/masking, SnapshotStore naming, and the Condition migration window | Deployment or production cutover proof |
| Runtime lifecycle | Stable post-migration semantics | The migration procedure itself |

The [release notes](https://github.com/Ahoo-Wang/Wow/releases) describe version changes. The selected tag's source,
tests, and build files are the exact contract; `main` is evidence for the current target only.

## Shared Completion Gates

Advance only when the current gate has reproducible evidence:

1. **Scope:** pin bounded context, source tag, target tag, datasets/stores, owners, and exclusions.
2. **Baseline:** make source tests green; inventory events/snapshots/keys/collections/read models; create and restore-test
   a backup.
3. **Rehearsal:** run the same migration tool and manifest against a production-shaped isolated copy.
4. **Verification:** compile, start, process, replay, reconcile, and gracefully stop the target; verify failure paths.
5. **Cutover:** stop admission, drain old writers, migrate once, start one target instance, then move a controlled
   traffic slice.
6. **Observation:** verify metrics/traces, backend versions, projection/BI lag, alerts, and business invariants.
7. **Closure:** remove old writers/data/bridges only after the rollback window ends.

Rollback must say what happens before and after the first target-version production write. Restoring only the old
binary after a new storage-format write is not a rollback.

## Legacy Link Navigation

The former single-page topics now live in the three focused guides. These headings and explicit aliases preserve old
deep links.

### Version Upgrade Guide

<span id="upgrade-steps"></span>
<span id="dependency-version-update"></span>
<span id="breaking-changes-check"></span>

See [v6 → v8: General Upgrade Steps](./migration/v6-to-v8.md#general-upgrade-steps).

### Migrating from Traditional Architecture

<span id="migration-strategy"></span>
<span id="gradual-migration"></span>
<span id="migration-steps"></span>

See [Traditional Architecture: Migration Overview](./migration/traditional-architecture.md#migration-overview).

### Data Migration

<span id="historical-data-import"></span>

See [Traditional Architecture: Import and Catch Up with One Writer](./migration/traditional-architecture.md#_2-import-and-catch-up-with-one-writer).

### Code Migration

<span id="from-crud-to-command-pattern"></span>
<span id="from-direct-queries-to-query-snapshots"></span>

See [Migrate the Boundary Before the Tables](./migration/traditional-architecture.md#_1-migrate-the-boundary-before-the-tables)
and [Reconcile, Then Move Reads and Writes Separately](./migration/traditional-architecture.md#_3-reconcile-then-move-reads-and-writes-separately).

### Compatibility Notes

<span id="data-format-compatibility"></span>
<span id="event-upgrades"></span>
<span id="message-format-compatibility"></span>

See [Continue Evolving the Domain Model](./migration/traditional-architecture.md#_4-continue-evolving-the-domain-model)
and [v6 → v8: Breaking Changes Check](./migration/v6-to-v8.md#breaking-changes-check).

### Known Issues

<span id="version-specific-issues"></span>
<span id="common-migration-issues"></span>

See the [Release Notes](https://github.com/Ahoo-Wang/Wow/releases) and
[Troubleshooting](./troubleshooting.md). Reproduce a failure against the exact pinned tag before applying a workaround.

### Migration Checklist

Use the [Traditional Architecture Completion Checklist](./migration/traditional-architecture.md#completion-checklist)
or [v6 → v8 Verification Checklist](./migration/v6-to-v8.md#verification-checklist), then add environment-specific
production admission evidence.

### Rollback Plan

Use the selected guide's rollback procedure and the before/after-first-write distinction in
[Shared Completion Gates](#shared-completion-gates).

### Unified Runtime Orchestration

See [Runtime Orchestration Migration](./migration/runtime-orchestration.md).

<span id="versioned-snapshot-checkpoint-removal"></span>

### Removal of Versioned Snapshot Checkpoints

See [v6 → v8: Versioned Snapshot Checkpoint Removal](./migration/v6-to-v8.md#versioned-snapshot-checkpoint-removal).

### Atomic SnapshotStore Saves

See [v6 → v8: Atomic SnapshotStore Saves](./migration/v6-to-v8.md#atomic-snapshotstore-saves).

### Redis EventStore Canonical v2 Layout (introduced in v8.9.0)

See [v6 → v8: Redis EventStore Canonical v2 Layout](./migration/v6-to-v8.md#redis-eventstore-canonical-v2-layout-introduced-in-v8-9-0).

### Aggregate Policies Move Off `@AggregateRoute` (9.3.0)

`@AggregateRoute(spaced, owner)` is deprecated in favour of `@Spaced` and `@AggregateOwner(OwnerPolicy.…)` on the aggregate, and conflicting declarations of a policy or of the static tenant now fail. See [Migrating from `@AggregateRoute(spaced, owner)`](./domain/aggregate.md#migrating-from-aggregateroute-spaced-owner).

### Compatibility Shims Removed (9.3.0)

9.3.0 removes declarations that only kept older bytecode or 9.2 extension code linking. Recompile libraries built against 9.2 or earlier before upgrading; source that uses the APIs listed below must migrate.

| Removed | Use instead |
|---|---|
| Overriding `MessageBus.receive(subscription)` in a bus | implement `receiver(subscription)`, which is now abstract |
| `MessageBus.runtimeReceiver(subscription)` | `receiver(subscription.copy(runtimeOwned = true))` |
| `MessageSubscription` constructors and `copy` without `runtimeOwned` (JVM only) | the constructors and `copy` with `runtimeOwned` (it defaults to `false`) |
| Redis bus constructors without `retentionOptions` (JVM only) | the primary constructors (`retentionOptions` defaults to `RedisStreamRetentionOptions.DEFAULT`) |
| 9.1 constructors of `BindingError`, `AggregationGroup.Terms` and `AggregationGroup.Histogram` (JVM only) | the primary constructors |
| `CoSecCommandBuilderExtractor`, `CoSecQueryRequestScope` | `CoSecIdentityHeaders.ALIASES`, registered by `CoSecAutoConfiguration` |
| `Flux<AggregateId>.toBatchResult(afterId)`, `ResendStateEventHandler.handle(afterId, limit)` | `toBatchResult(afterId, request, exceptionHandler)`, `resend(afterId, limit)` |
| Non-bean `WebFluxAutoConfiguration.commandMessageExtractor`, `queryRequestScope`, `commandRouterFunction` and `pointReadAdmission` overloads, `CoSecAutoConfiguration.coSecCommandBuilderExtractor` / `coSecQueryRequestScope`, the three-argument `OpenAPIAutoConfiguration.routerSpecs` | the `@Bean` methods of the same name |

These application-facing calls still compile in 9.3, deprecated, and are removed in 10.0.0:

| Deprecated | Use instead |
|---|---|
| Calling `bus.receive(subscription)` | `receiver(subscription).openedMessages()` |
| `ServerRequest.getTenantId(aggregateMetadata)`, `getTenantIdOrDefault(aggregateMetadata)` | `identity(aggregateMetadata).tenantId()` (`?: TenantId.DEFAULT_TENANT_ID`) |
| `ServerRequest.getOwnerId()` | `identity(aggregateMetadata).ownerId()` (for an aggregate owned by its ID, it falls back to `{id}`) |
| `ServerRequest.getSpaceId()`, `getSpaceId(aggregateRouteMetadata)` | `identity(aggregateMetadata).spaceId()` (`null` for an aggregate that is not spaced) |
| `ServerRequest.getAggregateId()` and its two `AggregateRoute.Owner` overloads | `identity(aggregateMetadata).aggregateId()` (the aggregate's owner policy applies) |
| `RecoverableExceptionRegistrar.register`, `unregister`, `getRecoverableType` (static calls; from Java through `.Companion`) | the same methods of `RecoverableExceptionRegistry.DEFAULT`, or a `RecoverableExceptionProvider` |

`identity(…)` is `me.ahoo.wow.webflux.route.identity.identity`; the `RequestIdentity` it returns reads each fact by the route's rules, header aliases included, exactly as the built-in command and query handlers do.

### Command Filters Replaced by a Fixed Pipeline (9.3.0)

The command side no longer has a filter chain. `DefaultCommandHandler` runs processing, acknowledgement, domain-event and state-event publication and the `PROCESSED` report in a fixed order (see [Command Processing Pipeline](./command/internals/pipeline.md#bus-to-dispatcher)). An `ExchangeFilter<ServerCommandExchange<*>>` bean is no longer called: move it to the extension point for what it did.

| Removed | Use instead |
|---|---|
| `CommandFilter`, or any `ExchangeFilter` with `@FilterType(CommandDispatcher::class)`, used for tracing, metrics or logging | A `CommandInstrumentation` bean; `around(exchange, handling)` wraps each command's handling and must return its outcome unchanged |
| A command filter that checked or rejected commands | `CommandValidator` / Jakarta validation on the command (checked at the gateway), or a check in the command function |
| A command filter that reacted to the committed events | An event processor, saga or projection |
| `TraceAggregateFilter` (OpenTelemetry) | `TraceCommandInstrumentation`, registered by the starter; same span name and attributes |
| `AggregateProcessorFilter`, `SendDomainEventStreamFilter`, `SendStateEventFilter`, `ProcessedNotifierFilter`, `DefaultCommandHandler(chain, errorHandler)` | `DefaultCommandHandler(serviceProvider, aggregateProcessorFactory, domainEventBus, stateEventBus, commandWaitNotifier, instrumentations, errorHandler)` |
| `CommandHandler.handle(exchange)` | `CommandHandler.handle(exchange, aggregateMetadata)` |
| `ServerCommandExchange.setAggregateMetadata` / `getAggregateMetadata` / `setAggregateProcessor` / `getAggregateProcessor` | The handler receives the metadata as a parameter |

The starter fails startup when the context still holds such a bean (an `ExchangeFilter<ServerCommandExchange<*>>`, or an `ExchangeFilter` with `@FilterType(CommandDispatcher::class)`), naming the bean and these replacements, instead of ignoring it.

### Command Gateway and Request-ID Check (9.3.0)

- The request ID is checked again on the node that processes the command, before the handler runs, against its `EventStore`. `NoopRequestIdExistenceChecker`, used by a node without an `EventStore`, now answers "absent" instead of "exists". A gateway-only service therefore no longer rejects a command on a Bloom-filter false positive, and a resent command is rejected by the processing node instead. A resent `@VoidCommand` is no longer rejected by such a gateway; see [Failures and Idempotency](./command/reliability.md#fast-precheck-and-authoritative-confirmation).
- Upgrade processing nodes before gateway-only services, and note that the processing node rejects a resend without re-running the handler only within its Bloom-filter window; outside it the `EventStore` append rejects it, as in 9.2.
- `DefaultCommandGateway.close()` no longer closes the `CommandBus` it was given. After `close()` the gateway cannot schedule deadlines: `sendAndWait*` fails with `RejectedExecutionException`. Code that builds a gateway by hand closes its bus itself; Spring closes the bus bean.
- `sendAndWait` / `sendAndWaitStream` send a copy of the message with the wait keys in its Header; the caller's message is not modified. Code that read wait keys back from the message it passed must read them from the received message instead.

### Header Propagation and Recoverable Exceptions Are Beans (9.3.0)

- `MessagePropagatorProvider` is removed. Use an injected `MessagePropagators` (the Spring bean, or `MessagePropagators.DEFAULT` outside Spring); `import ...MessagePropagatorProvider.propagate` becomes `import me.ahoo.wow.messaging.propagation.propagate`. A `MessagePropagator` can now also be a bean; a bean wins over a ServiceLoader propagator of the same class, and Wow's `@Order` (not Spring's) orders them.
- `RecoverableExceptionRegistrar` is now the interface `RecoverableExceptionProvider`s register into; the static object is removed. Use `RecoverableExceptionRegistry.DEFAULT` or the `recoverableExceptionRegistry` bean (`register`, `unregister`, `getRecoverableType`). A `RecoverableExceptionProvider` can now also be a bean.
- `wow.messaging.propagation.request` is read from the Spring environment and applies to the runtime's injected `MessagePropagators` only; `MessagePropagators.DEFAULT` ignores it, and outside Spring the `-D` system property is no longer read.
- A chain wait's tail reaches only the commands of the Saga function the chain waits for, and a chain plan must wait on the command it is sent with (`waitCommandId` = command ID), otherwise `sendAndWait` fails with `IllegalArgumentException`. See [Command Wait Runtime](./command/internals/wait-runtime.md).

### BI Script Route Needs `wow-bi` (9.3.0)

`wow-webflux` and the Starter's `webflux-support` / `openapi-support` capabilities no longer bring `wow-bi` (and the ClickHouse client). An application that serves `POST /wow/bi/script` adds `wow-bi`, or requests the Starter's `bi-support` capability; with it on the classpath the route, its OpenAPI operation and schemas, its error codes and the `wow.bi.script.*` properties are unchanged. Without it the route is absent. The BI route classes moved to the Starter:

| Removed | Use instead |
|---|---|
| `me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunction` / `GenerateBIScriptHandlerFunctionFactory` | wired by `me.ahoo.wow.spring.boot.starter.bi.BiAutoConfiguration` (internal) |
| `me.ahoo.wow.spring.boot.starter.webflux.bi.BiDeploymentInspectorAutoConfiguration` | `me.ahoo.wow.spring.boot.starter.bi.BiAutoConfiguration` |
| `GenerateBIScriptRouteContributor` in `DefaultRouteContributors.all()` | a `RouteContributor` bean the Starter registers when `wow-bi` is present; the route is served only through the Starter |

### Failure Policy: Receive Retry and Failure Recording (9.3.0)

- Receive retry is the core `TransportFailurePolicy` for every transport. Redis Streams now retries a failed receive stream like Kafka (3 consecutive retries from `10s`, `wow.redis.message-bus.receiver.retry-*`) instead of stopping the runtime on the first error. `KafkaReceiverPolicy.retrySpec`, `DEFAULT_RETRY_ATTEMPTS`, `DEFAULT_RETRY_BACKOFF` and `defaultRetrySpec(...)` are removed: build `TransportFailurePolicy(TransportFailurePolicy.receiveRetry(attempts, backoff))` and pass it as `failurePolicy` to `KafkaTransport` or a Kafka/Redis bus, or override the `kafkaTransportFailurePolicy` / `redisTransportFailurePolicy` bean. The `wow.kafka.receiver.retry-*` keys are unchanged.
- Kafka `RetriableException`s and Redis connection failures and timeouts are `RECOVERABLE`, so `RetryableFilter` and the event-store append resolution retry them. A projection or saga that does a non-idempotent write (for example Redis `INCR`) can therefore run it again in-process after a timeout whose write did land; delivery was already at-least-once, keep such writes idempotent.
- With Redis down at startup, a Redis receiver's readiness now fails only after the retry policy is exhausted (about 70 s with the defaults) instead of at once.
- A compensation record (`ExecutionFailed`) for a failure whose in-process retries were exhausted now carries the cause's error code, message, stack trace and `recoverable` (9.2 recorded `IllegalState` "Retries exhausted: n/n" and `UNKNOWN`). A cause declared unrecoverable (for example with `@Retry(unrecoverable = …)`) is therefore no longer compensated automatically.
- The unused `me.ahoo.wow.messaging.handler.retryStrategy(...)` is removed; use Reactor's `Retry.backoff`.
- The compensation module records event-processing failures as a `FailureRecorder` (`CompensationFailureRecorder`) instead of filters: `DomainEventCompensationFilter`, `StateEventCompensationFilter` and `EventCompensationFilter` are removed, and the `domainEventCompensationFilter` / `stateEventCompensationFilter` beans are replaced by `compensationFailureRecorder`. The commands it sends are byte-identical to 9.2. The record is now written after the wait notifier signals (see [Failure Recording](./event/dispatch.md#failure-recording)).
- New `wow.event.ack-on-unrecorded-failure` (default `true`, unchanged behaviour): set `false` to leave a failure no recorder recorded unacknowledged for redelivery.
- `DefaultDomainEventHandler`, `DefaultProjectionHandler`, `DefaultStatelessSagaHandler` and `DefaultSnapshotHandler` extend `FailureRecordingHandler` and take an optional `failureRecorder` (and, except the snapshot one, `ackOnUnrecordedFailure`).

### Mongo Ownership Guard

See [v6 → v8: Mongo Ownership Guard](./migration/v6-to-v8.md#mongo-ownership-guard).

## Related Pages

| Page | Relationship |
|---|---|
| [Migrating from Traditional Architecture](./migration/traditional-architecture.md) | First adoption and traffic ownership |
| [Migrate Wow v6 to v8](./migration/v6-to-v8.md) | Existing Wow platform/storage upgrade |
| [Runtime Orchestration Migration](./migration/runtime-orchestration.md) | Unified lifecycle source migration |
| [V9 Query Migration](./query/v9-query-migration.md) | V8.16.x to V9 query and SnapshotStore source migration |
| [Migrate from Fetcher Packages](./typescript/migration.md) | TypeScript package renames and client regeneration |
| [Runtime Lifecycle](./advanced/runtime-lifecycle.md) | Stable runtime model after migration |
| [Troubleshooting](./troubleshooting.md) | Evidence-first diagnosis when a gate fails |

<!-- Sources: current migration subpages, v6/v8 tags, WowRuntime, SnapshotStore, Redis/Mongo guards -->
