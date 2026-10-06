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
| Wow 9.2.x | [Upgrading from 9.2 to 9.3.0](#upgrading-from-9-2-to-9-3-0) | Recompile, migrate the removed and deprecated APIs, and roll out processing nodes before gateway-only services; REST, storage and wire formats are unchanged |
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

## Upgrading from 9.2 to 9.3.0

9.3.0 reworks the write side, the transports and the API tiers. REST routes and bodies, the stored formats, the message JSON and the Kafka topics and consumer groups do not change, so 9.2.x and 9.3.0 nodes can share one cluster during a rolling upgrade. The Kotlin API does change: 9.3.0 keeps **no binary-compatibility shims** (declarations kept only so that 9.2 bytecode links), and application-facing APIs it replaces keep **one `@Deprecated` cycle** and are removed in 10.0.0, as listed in [compatibility debt](https://github.com/Ahoo-Wang/Wow/blob/main/docs/compat-debt.md). Extension-point SPI that only backend, transport or framework implementers touch changes without a deprecation cycle. The [9.3.0 release notes](https://github.com/Ahoo-Wang/Wow/releases/tag/v9.3.0) list every change with its pull request.

The sections below are ordered by how likely an application is to meet them: every application reads the first four; the later ones concern custom extensions.

<!--
Placeholders for 9.3.0 work not merged when this section was written. Add a section here, in order of likelihood, when one lands; delete the line when it moves to a later release.
- X7 (shared keyed executor, per-context receivers; #3969, #3975): may move to 9.4. Would deprecate AggregateSchedulerSupplier and the parallelism settings, add wow.dispatch.*, and needs a rolling-upgrade note.
- X4 (hot path: meter caching).
- B8 (shutdown under sustained ingress).
-->

### Before You Upgrade

1. **Move to 9.2.4 first** if you run an older 9.2.x: the mixed-version gate (`MixedVersionClusterTest`) runs the released 9.2.4 image beside the 9.3 build on one MongoDB and one Kafka, in the same consumer groups, and proves commands, snapshots, sagas and local-first copies cross versions in both directions.
2. **Recompile every application and library against 9.3.0.** A library built against 9.2 may not link (see [Recompile](#recompile-removed-shims-and-deprecated-calls)).
3. **Roll out processing nodes before gateway-only services.** A processing node hosts aggregates and an `EventStore`; a gateway-only service only sends commands. A 9.3 gateway-only service lets a request-ID Bloom hit through, and a 9.2 processing node does not check request IDs before the handler, so in the other order a resent command would run its handler again until the append rejects it.
4. **Keep the mixed window short.** While both versions run, the same request can get a different answer depending on the node:

| Situation | 9.2 node | 9.3 node |
|---|---|---|
| A body or header contradicts the tenant or owner the route fixes | accepted (the body wins) | `400 IllegalArgument` |
| An unexpected exception on an SSE stream or in a batch result | `BadRequest` with the exception's message | `InternalServerError`, "Unexpected server error" |
| A create resent with its own request ID, outside the request-ID window | `DuplicateAggregateId` | `DuplicateRequestId` |
| `aggregateVersion` of a command whose append failed (processing node) | N+1 (not stored) | N (committed) |
| A saga create retried after a failure | every attempt creates a new aggregate (random ID) | the retry is rejected as `DuplicateRequestId`; a 9.2 attempt and a 9.3 attempt can still create two aggregates |

### Recompile: Removed Shims and Deprecated Calls

These declarations only kept older bytecode or 9.2 extension code linking, and are removed. Source that uses them must migrate:

| Removed | Use instead |
|---|---|
| Overriding `MessageBus.receive(subscription)` in a bus | implement `receiver(subscription)`, which is now abstract |
| `MessageBus.runtimeReceiver(subscription)` | `receiver(subscription.copy(runtimeOwned = true))`; a bus branches on `subscription.runtimeOwned` |
| `MessageSubscription` constructors and `copy` without `runtimeOwned` (JVM only) | the constructors and `copy` with `runtimeOwned` (it defaults to `false`) |
| Redis bus constructors without `retentionOptions` (JVM only) | the primary constructors (`retentionOptions` and `failurePolicy` have defaults) |
| 9.1 constructors of `BindingError`, `AggregationGroup.Terms` and `AggregationGroup.Histogram` (JVM only) | the primary constructors |
| `CoSecCommandBuilderExtractor`, `CoSecQueryRequestScope` | `CoSecIdentityHeaders.ALIASES`, registered by `CoSecAutoConfiguration` as an `IdentityHeaderAliases` bean |
| `Flux<AggregateId>.toBatchResult(afterId)`, `ResendStateEventHandler.handle(afterId, limit)` | `toBatchResult(afterId, request, exceptionHandler)`, `resend(afterId, limit)` |
| Non-bean `WebFluxAutoConfiguration.commandMessageExtractor`, `queryRequestScope`, `commandRouterFunction` and `pointReadAdmission` overloads, `CoSecAutoConfiguration.coSecCommandBuilderExtractor` / `coSecQueryRequestScope`, the three-argument `OpenAPIAutoConfiguration.routerSpecs` | the `@Bean` methods of the same name |

These application-facing calls still compile in 9.3, deprecated, and are removed in 10.0.0:

| Deprecated | Use instead |
|---|---|
| `@AggregateRoute(spaced = …, owner = …)`, `AggregateRoute.Owner` | `@Spaced`, `@AggregateOwner(OwnerPolicy.…)` (next section) |
| Calling `bus.receive(subscription)` | `receiver(subscription).openedMessages()` |
| `ServerRequest.getTenantId(aggregateMetadata)`, `getTenantIdOrDefault(aggregateMetadata)` | `identity(aggregateMetadata).tenantId()` (`?: TenantId.DEFAULT_TENANT_ID`) |
| `ServerRequest.getOwnerId()` | `identity(aggregateMetadata).ownerId()` (for an aggregate owned by its ID, it falls back to `{id}`) |
| `ServerRequest.getSpaceId()`, `getSpaceId(aggregateRouteMetadata)` | `identity(aggregateMetadata).spaceId()` (`null` for an aggregate that is not spaced) |
| `ServerRequest.getAggregateId()` and its two `AggregateRoute.Owner` overloads | `identity(aggregateMetadata).aggregateId()` (the aggregate's owner policy applies) |
| `RecoverableExceptionRegistrar.register`, `unregister`, `getRecoverableType` (static calls; from Java through `.Companion`, since 9.2's `INSTANCE` is gone) | the same methods of `RecoverableExceptionRegistry.DEFAULT`, or a `RecoverableExceptionProvider` |
| `Throwable.toResponseEntity()`, `ErrorInfo.toServerResponse()` | `WebFluxErrorStrategy.toServerResponse`, or the `RequestExceptionHandler` bean |

`identity(…)` is `me.ahoo.wow.webflux.route.identity.identity`; the `RequestIdentity` it returns reads each fact by the route's rules, header aliases included, exactly as the built-in command and query handlers do. The deprecated readers delegate to it, so they also reject a blank identity path variable the route declares (400) and apply the conflict checks of [Requests clients can see](#requests-clients-can-see).

### Aggregate Policies Move Off `@AggregateRoute`

`@AggregateRoute(spaced, owner)` is deprecated in favour of `@Spaced` and `@AggregateOwner(OwnerPolicy.…)` on the aggregate; the old attributes are still read when the new annotation is absent, so each aggregate's effective policy is unchanged. See [Migrating from `@AggregateRoute(spaced, owner)`](./domain/aggregate.md#migrating-from-aggregateroute-spaced-owner).

Conflicting declarations no longer let one silently win:

- `@Spaced` / `@AggregateOwner` and `@AggregateRoute(spaced, owner)` with different values on one class, or `@StaticTenantId` and a different `tenantId` from `@BoundedContext.Aggregate` or a hand-written `wow-metadata.json`, fail at startup (`IllegalStateException`) and at compile time (KSP), naming both values. An api/domain split that declares the tenant in one jar and `@StaticTenantId` in the other is checked too.
- Two `wow-metadata.json` resources on the classpath that disagree (one aggregate with different tenants or types, one context with different aliases) fail startup naming both URLs; 9.2 logged an error and dropped the second resource, depending on classpath order. A resource that cannot be parsed is still logged and skipped.

### Aggregate Tests Run the Production Kernel

The aggregate test DSL (`AggregateSpec`, `aggregateVerifier`) runs each command through the production pipeline and kernel. Its API is unchanged, but assertions written against the old DSL's own behaviour change: a non-create command without history fails with `NotFoundResourceException`, a create after given events fails with `DuplicateAggregateIdException` from the append, a command function that returns nothing passes with no event stream, given events are stored and every step reloads the state, and a failed command reports `domainEventStream == null`. Sibling `whenCommand`s of one given stage stay independent, as in 9.2. A recoverable failure is reported at once, not retried. Every difference is in [the test suite's table](./test-suite.md#the-same-pipeline-as-production).

### Requests Clients Can See

- **Contradictory identity is rejected.** A command body `@TenantId` / `@OwnerId`, or a `Command-Tenant-Id` / `Command-Owner-Id` header, that differs from the tenant or owner the route fixes (static tenant, `{tenantId}`, `{ownerId}`, or `{id}` of an aggregate owned by its ID) answers `400 IllegalArgument`; a tenant header against a static tenant is still ignored. A client or gateway that sends one global `Command-Owner-Id` or `Command-Tenant-Id` on every request must drop it where the path states the fact. See [Request Identity](./open-api.md#request-identity), which also covers the owner taken from `{id}` and percent-encoded IDs.
- A blank `{id}` on the event-load, compensate, regenerate and tracing routes answers 400, like every other route; a blank `CoSec-Space-Id` / `CoSec-Request-Id` counts as absent.
- An SSE error event or batch result for an unexpected exception is `InternalServerError` with "Unexpected server error", as on JSON routes, instead of `BadRequest` with the exception's message. See [Error Handling](./extensions/webflux.md#error-handling).
- A command whose append failed reports the committed `aggregateVersion` N in its `CommandResult` and wait signal, not the unstored N+1.
- A create resent with the request ID that created the aggregate is `DuplicateRequestId`, also outside the request-ID window (a 9.2 bug: it reported `DuplicateAggregateId` there, although the request is a replay). The HTTP status stays 400. A create with another request ID is still `DuplicateAggregateId`.

### Command Filters Replaced by a Fixed Pipeline

The command side no longer has a filter chain. `DefaultCommandHandler` runs processing, acknowledgement, domain-event and state-event publication and the `PROCESSED` report in a fixed order (see [Command Processing Pipeline](./command/internals/pipeline.md#bus-to-dispatcher)). An `ExchangeFilter<ServerCommandExchange<*>>` bean is no longer called; the starter fails startup when the context holds one (or an `ExchangeFilter` with `@FilterType(CommandDispatcher::class)`), naming the bean and these replacements:

| Removed | Use instead |
|---|---|
| `CommandFilter`, or any `ExchangeFilter` with `@FilterType(CommandDispatcher::class)`, used for tracing, metrics or logging | A `CommandInstrumentation` bean; `around(exchange, handling)` wraps each command's handling and must return its outcome unchanged |
| A command filter that checked or rejected commands | `CommandValidator` / Jakarta validation on the command (checked at the gateway), or a check in the command function |
| A command filter that reacted to the committed events | An event processor, saga or projection |
| `TraceAggregateFilter` (OpenTelemetry) | `TraceCommandInstrumentation`, registered by the starter; same span name and attributes |
| `AggregateProcessorFilter`, `SendDomainEventStreamFilter`, `SendStateEventFilter`, `ProcessedNotifierFilter`, `DefaultCommandHandler(chain, errorHandler)` | `DefaultCommandHandler(serviceProvider, aggregateProcessorFactory, domainEventBus, stateEventBus, commandWaitNotifier, instrumentations, requestIdChecker, errorHandler)` (`@InternalWowApi`) |
| `CommandHandler.handle(exchange)` | `CommandHandler.handle(exchange, aggregateMetadata)` |
| `ServerCommandExchange.setAggregateMetadata` / `getAggregateMetadata` / `setAggregateProcessor` / `getAggregateProcessor` | The handler receives the metadata as a parameter |
| Starter beans `aggregateProcessorFilter`, `sendDomainEventStreamFilter`, `commandFilterChain`, `sendStateEventFilter`, `processedNotifierFilter`, `traceAggregateFilter` | `commandHandler`, `traceCommandInstrumentation` |

### Command Kernel and Failed Commands

A command still decides, applies its events, then appends them, as in 9.2, now as one atomic unit (see [Decide, apply, then append](./command/internals/pipeline.md#decide-apply-then-append)):

- When a sourcing function throws or the append fails, the command fails and nothing is stored or published; an instance that applied events which were not stored is discarded, never reused.
- `@OnError` runs on the committed state, reloaded when the failed attempt applied unstored events (9.2 gave it the state with those events applied). If the reload fails, `@OnError` is skipped and the original error is returned with the load error suppressed.
- The `CommandResult` / wait signal of a failed append reports the committed `aggregateVersion` N.
- `VersionAware.version` is set after every sourcing function of the stream ran: a sourcing function that reads `this.version` sees the previous version.

Each aggregate type is compiled once at startup:

- A command whose handler is declared with a superclass or interface parameter is now handled by the nearest such handler instead of failing as undefined. After-command and `@OnError` functions still match the command's own type. Duplicate handlers and non-nullable injected parameters that resolve to nothing log a WARN.
- An exception thrown by a function returning `Flow` before it returns the flow arrives unwrapped, not as `InvocationTargetException`, for command, event, saga and projection functions.
- `StateAggregateMetadata.toMessageFunctionRegistry(stateRoot)` is removed; source state through `StateAggregate.onSourcing(eventStream)`.
- The reactive accessor classes (`SimpleMonoFunctionAccessor`, `SyncMonoFunctionAccessor`, `FluxMonoFunctionAccessor`, `PublisherMonoFunctionAccessor`, `FlowMonoFunctionAccessor`, `SuspendMonoFunctionAccessor`, `BlockingMonoFunctionAccessor`, `AbstractMonoFunctionAccessor`) are removed; use `KFunction.toMonoFunctionAccessor()` or `MonoMethodAccessorFactory.create(function)`. `toBlockable` moved to the `BlockableKt` facade (Java callers).

### Command Gateway and Request-ID Check

- The node that processes a command checks its request ID before the handler runs, against its own Bloom filter and its `EventStore`. `NoopRequestIdExistenceChecker`, used by a node without an `EventStore`, now answers "absent" instead of "exists". A gateway-only service therefore no longer rejects a command on a Bloom-filter false positive, and a resent command is rejected by the processing node (`DuplicateRequestId`) without running the handler. A caller that waits only for `SENT` on a gateway-only service no longer sees the duplicate; wait for `PROCESSED` to see it. A resent `@VoidCommand` is no longer rejected by such a gateway. See [Failures and Idempotency](./command/reliability.md#fast-precheck-and-authoritative-confirmation).
- The processing node rejects a resend without re-running the handler only within its Bloom-filter window; outside it the `EventStore` append rejects it, as in 9.2. `wow.command.idempotency.enabled=false` turns off both checks.
- Outside Spring, the processing-node check runs only when the `DefaultCommandHandler` is given a `RequestIdChecker`; without one, the `EventStore` append is that node's only duplicate check, as in 9.2.
- `DefaultCommandGateway.close()` no longer closes the `CommandBus` it was given. After `close()` the gateway cannot schedule deadlines: `sendAndWait*` fails with `RejectedExecutionException`. Code that builds a gateway by hand closes its bus itself; Spring closes the bus bean.
- `sendAndWait` / `sendAndWaitStream` send a copy of the message with the wait keys in its Header; the caller's message is not modified. Code that read wait keys back from the message it passed must read them from the received message instead.

### Sagas, Waits and Ordering

- Saga command IDs are derived from the event. A command body or `CommandBuilder` that names no aggregate gets an aggregate ID derived from the event, the saga function, its index and the target type, in the target generator's format (only for a time-based CosId or Snowflake generator; segment and custom generators keep random IDs, and a returned `CommandMessage` keeps its aggregate ID). A returned `CommandMessage` without its own request ID gets `<event ID>-<index>-<producer hash>`; bodies and builders keep `<event ID>-<index>`, as in 9.2. A retried saga create therefore targets the aggregate the first attempt created and is rejected as `DuplicateRequestId`; the saga skips that send, so no compensation record is written for it. See [requestId and Context Propagation](./event/saga.md#requestid-and-context-propagation).
- A chain wait's tail reaches only the commands of the saga function the chain waits for, so a chain-wait SSE stream (`sendAndWaitStream` with a chain target) no longer carries the tail signals of other sagas reacting to the same event. A chain plan must wait on the command it is sent with (`waitCommandId` = command ID), otherwise `sendAndWait` fails with `IllegalArgumentException`. See [Command Wait Runtime](./command/internals/wait-runtime.md).
- `@Order` is resolved by a topological sort of the `before`/`after` constraints (lowest `value` first among the elements that are ready, then declaration order), for every ordered list: filter chains, after-command functions, event upgraders, ID generators, error-info converters, query admission and message propagators. The result can differ from 9.2's even where 9.2's order already met every constraint (`X @Order(100, before = [Y])`, `Y @Order(0)`, `Z @Order(50)`: 9.2 `[X, Y, Z]`, 9.3 `[Z, X, Y]`), and a cycle in the constraints fails startup with an `IllegalStateException` naming it.

### BI Script Route Needs `wow-bi`

`wow-webflux` and the Starter's `webflux-support` / `openapi-support` capabilities no longer bring `wow-bi` (and the ClickHouse client). An application that serves `POST /wow/bi/script` adds `wow-bi`, or requests the Starter's `bi-support` capability; with it on the classpath the route, its OpenAPI operation and schemas, its error codes and the `wow.bi.script.*` properties are unchanged. Without it the route is absent. The BI route classes moved to the Starter:

| Removed | Use instead |
|---|---|
| `me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunction` / `GenerateBIScriptHandlerFunctionFactory` | wired by `me.ahoo.wow.spring.boot.starter.bi.BiAutoConfiguration` (internal) |
| `me.ahoo.wow.spring.boot.starter.webflux.bi.BiDeploymentInspectorAutoConfiguration` | `me.ahoo.wow.spring.boot.starter.bi.BiAutoConfiguration` |
| `GenerateBIScriptRouteContributor` in `DefaultRouteContributors.all()` | a `RouteContributor` bean the Starter registers when `wow-bi` is present; the route is served only through the Starter |

`BiScriptOptions` and `BiScriptProperties` gained `omitSensitiveFields` (`wow.bi.script.omit-sensitive-fields`, default `false`), which changes their JVM constructor and `copy` signatures; with it on, expansion columns leave out `@Sensitive` state properties. Generated scripts are unchanged by default.

### Local-First Delivery

- A local-first send completes when the message is handed off to the local receivers, not when the local dispatcher admitted it, and no longer waits for its distributed copy. The copy is sent asynchronously, in send order per aggregate, with the same `local_first` marker as 9.2. A failed copy is logged and counted, and no longer fails the send. See [LocalFirst dual-copy admission](./command/internals/transport.md#localfirst-dual-copy-admission).
- Local sinks for domain and state events are unbounded: a slow local consumer grows the backlog (gauge `wow.local_first.backlog`, a warning at `wow.{command,event,eventsourcing.state}.bus.local-first.backlog-high-water-mark`, default 10000) instead of being bypassed.
- Queued copies are sent within `wow.shutdown-timeout` and cancelled after it. A handed-off message that was not yet processed is lost if the process crashes; disable local-first where a message must survive a crash. There is no ordering guarantee across topics or buses.
- `LocalMessageBus.sendIfSubscribed(message): Mono<Boolean>` is replaced by `handOff(message): Mono<LocalHandoff>`, and `LocalFirstMessageBus` has an abstract `distributedCopies: LocalFirstDistributedCopies`, which the three built-in local-first buses take as a constructor parameter. The Starter registers one `localFirst{Command,DomainEvent,StateEvent}BusDistributedCopies` bean per bus, as a runtime component. A `LocalFirstDistributedCopies` created outside the runtime is not awaited at shutdown unless it is registered as a runtime component.

### Failure Policy: Receive Retry and Failure Recording

- Receive retry is the core `TransportFailurePolicy` for every transport. Redis Streams now retries a failed receive stream like Kafka (3 consecutive retries from `10s`, `wow.redis.message-bus.receiver.retry-*`) instead of stopping the runtime on the first error. With Redis down at startup, a Redis receiver's readiness now fails only after the retry policy is exhausted (about 70 s with the defaults) instead of at once.
- `KafkaReceiverPolicy.retrySpec`, `DEFAULT_RETRY_ATTEMPTS`, `DEFAULT_RETRY_BACKOFF` and `defaultRetrySpec(...)` are removed: build `TransportFailurePolicy(TransportFailurePolicy.receiveRetry(attempts, backoff))` and pass it as `failurePolicy` to `KafkaTransport` or a Kafka/Redis bus, or override the `kafkaTransportFailurePolicy` / `redisTransportFailurePolicy` bean. The `wow.kafka.receiver.retry-*` keys are unchanged.
- Kafka `RetriableException`s and Redis connection failures and timeouts are `RECOVERABLE`, so `RetryableFilter` and the event-store append resolution retry them. A projection or saga that does a non-idempotent write (for example Redis `INCR`) can therefore run it again in-process after a timeout whose write did land; delivery was already at-least-once, keep such writes idempotent.
- A compensation record (`ExecutionFailed`) for a failure whose in-process retries were exhausted now carries the cause's error code, message, stack trace and `recoverable` (9.2 recorded `IllegalState` "Retries exhausted: n/n" and `UNKNOWN`). A cause declared unrecoverable (for example with `@Retry(unrecoverable = …)`) is therefore no longer compensated automatically.
- The compensation module records event-processing failures as a `FailureRecorder` (`CompensationFailureRecorder`) instead of filters: `DomainEventCompensationFilter`, `StateEventCompensationFilter` and `EventCompensationFilter` are removed, and the `domainEventCompensationFilter` / `stateEventCompensationFilter` beans are replaced by `compensationFailureRecorder`, which an application `FailureRecorder` bean replaces. The commands it sends are byte-identical to 9.2. The record is now written after the wait notifier signals, so poll `ExecutionFailed` by event ID rather than expecting it with the signal (see [Failure Recording](./event/dispatch.md#failure-recording)).
- New `wow.event.ack-on-unrecorded-failure` (default `true`, unchanged behaviour): set `false` to leave a failure no recorder recorded unacknowledged for redelivery.
- `DefaultDomainEventHandler`, `DefaultProjectionHandler`, `DefaultStatelessSagaHandler` and `DefaultSnapshotHandler` extend `FailureRecordingHandler` and take an optional `failureRecorder` (and, except the snapshot one, `ackOnUnrecordedFailure`).
- The unused `me.ahoo.wow.messaging.handler.retryStrategy(...)` is removed; use Reactor's `Retry.backoff`.

### Redis Streams: Retention and Idle Consumers

- A starting Redis receiver deletes consumers of its group that have nothing pending and have been idle for `wow.redis.message-bus.retention.consumer-idle-timeout` (default `30m`), atomically, so no message is lost; `XINFO CONSUMERS` stops growing with every deploy. Set `wow.redis.message-bus.retention.reap-idle-consumers: false` to keep every consumer.
- Stream trimming is available and off by default: `max-length` (`MAXLEN`) or `max-age` (`MINID`, at least 1 minute), approximate (`~`) unless `approximate: false`. It needs Redis 7.0 or later, and a lagging group loses entries trimmed before it read them. See [Stream retention and idle consumers](./extensions/redis.md#stream-retention-and-idle-consumers).
- The three Redis bus beans of `RedisMessageBusAutoConfiguration` take the new `RedisStreamRetentionProperties`.

### Shutdown: Writers and Senders Join the Runtime

- Mongo and Elasticsearch batch writers and Kafka senders stop after the dispatchers, within the runtime's one `wow.shutdown-timeout`, instead of being closed by Spring afterwards (up to 30 s per batch writer, one after another). Size `wow.shutdown-timeout` to fit a normal flush. See [Storage and transport resources](./advanced/runtime-lifecycle.md#storage-and-transport-resources).
- In the default Starter runtime `WowRuntime.components` now starts with a `RuntimeResources` component; a custom runtime should put one first too. Tests that assert the exact component list filter it out.
- The Kafka producer close is bounded by `wow.kafka.close-timeout`, default `wow.shutdown-timeout`. `KafkaProperties` takes a trailing `closeTimeout`, `buildSenderOptions(defaultCloseTimeout)` applies it, else the given default, and code that constructs `KafkaAutoConfiguration` by hand passes `WowProperties` too.

### Header Propagation and Recoverable Exceptions Are Beans

- `MessagePropagatorProvider` is removed. Use an injected `MessagePropagators` (the Spring bean, or `MessagePropagators.DEFAULT` outside Spring); `import ...MessagePropagatorProvider.propagate` becomes `import me.ahoo.wow.messaging.propagation.propagate`. `toCommandMessage(…)`, `CommandBuilder.toCommandMessage()`, `toDomainEventStream(…)` and the constructors of `SimpleCommandMessageFactory`, `SimpleCommandAggregateFactory`, `StatelessSagaFunction` and `StatelessSagaFunctionRegistrar` take a trailing `MessagePropagators` (defaulted). A `MessagePropagator` can now also be a bean; a bean wins over a ServiceLoader propagator of the same class, and Wow's `@Order` (not Spring's) orders them.
- `RecoverableExceptionRegistrar` is now the interface `RecoverableExceptionProvider`s register into; its 9.2 static calls remain as deprecated companion functions (see [Recompile](#recompile-removed-shims-and-deprecated-calls)). Use `RecoverableExceptionRegistry.DEFAULT` or the `recoverableExceptionRegistry` bean. A `RecoverableExceptionProvider` can now also be a bean.
- `wow.messaging.propagation.request` is read from the Spring environment, so it can be set in `application.yaml`; it applies to the runtime's injected `MessagePropagators` only. `MessagePropagators.DEFAULT` ignores it, and outside Spring the `-D` system property is no longer read.

### API Tiers: `@WowSpi` and `@InternalWowApi`

Two markers now separate what applications use from what implementers use: `@WowSpi` (opt-in, warning level) marks SPI for backend, transport and command-aggregate implementers, and `@InternalWowApi` (opt-in, error level) marks internals other Wow modules wire. Add `@OptIn(WowSpi::class)` or the compiler flag `-opt-in=me.ahoo.wow.api.annotation.WowSpi` where you implement an SPI.

| Module | Became internal or `@InternalWowApi` | Use instead |
|---|---|---|
| wow-core | `SimpleCommandAggregate`, `CommandState`, `CommandAggregate.commandState`, `RetryableAggregateProcessor`, `CommandFunction` and its implementations, `AfterCommandFunction`, `AggregateProcessorFactory`, `RetryableAggregateProcessorFactory`, `SimpleStateAggregate`, `FunctionAccessorMetadata`, `MessageFunctionAccessor` and its implementations, `MessageFunctionRegistrar`, `FunctionMetadataParser`, the event-dispatcher bases, `CompositeEventDispatcher`, `EventHandler`, the exchange attribute keys and the `ServerCommandExchange` attribute accessors other than `getEventStream()` / `getAggregateVersion()`, `commandSentSignal`, the `toResult` factories | `@OnCommand` / `@AfterCommand` functions, `MessageFunction`, `StateAggregateFactory`, `ServerCommandExchange.getEventStream()` / `getAggregateVersion()`, `CommandGateway` results |
| wow-query | `QueryOperation`, `QueryAdmission.admit(QueryOperation, …)`, `QueryEntryPolicy.requireScope`, `UnavailableQueryModelSchemaProvider`, `FilterNormalizer`, `DefaultQueryModelSchemaProvider`, `QueryFieldSchema`, `QueryModelSchema.field`, `QueryFieldCapabilities` | the gateway or `QueryAdmission.Trusted.*`; `QueryModelCompiler` / `QuerySchemaCatalog`; `QueryModelSchema.describe(...)` |

`CommandAggregate`, `AggregateProcessor`, `CommandAggregateFactory`, `SimpleCommandAggregateFactory`, the query backend SPI (see [API tiers](./query/query-backend.md#api-tiers)) and the transport SPI are `@WowSpi`. A command function that needs the current state takes a `ReadOnlyStateAggregate<S>` parameter instead of a `CommandAggregate`. Third-party query backends that run the TCK now get `schema refresh with unchanged storage keeps the published object`: a refresh that changes nothing keeps the published schema.

### Transport SPI: Kafka and Redis Bus Internals Removed

Every distributed bus is now a core `TransportMessageBus` over a `Transport` (see [Transport SPI](./command/internals/transport.md#transport-spi)). The per-backend bus base classes, exchanges and decode handlers were an extension-point SPI and are removed, not deprecated; topics, keys, JSON and consumer groups are unchanged.

| Removed | Use instead |
|---|---|
| `AbstractKafkaBus`, `AbstractRedisMessageBus` | `KafkaTransport` / `RedisStreamTransport` under the core `TransportCommandBus`, `TransportDomainEventBus`, `TransportStateEventBus`; the `Kafka*Bus` / `Redis*Bus` option constructors keep their parameters apart from the decode handler below and the trailing, defaulted `failurePolicy` |
| `KafkaServerCommandExchange`, `KafkaEventStreamExchange`, `KafkaStateEventExchange`, `RedisServerCommandExchange`, `RedisEventStreamExchange`, `RedisStateEventExchange` | `TransportServerCommandExchange`, `TransportEventStreamExchange`, `TransportStateEventExchange` |
| `KafkaRecordDecodeFailureHandler`, `KafkaRecordDecodeFailure`, `KafkaRecordDecodeException` | `TransportDecodeFailureHandler`, `TransportDecodeFailure`, `TransportDecodeException` |
| `FailKafkaRecordDecodeFailureHandler`, `AcknowledgeKafkaRecordDecodeFailureHandler` | `TransportDecodeFailureHandler.FAIL`, `TransportDecodeFailureHandler.ACKNOWLEDGE` |
| `Kafka*Bus(…, recordDecodeFailureHandler = …)` | `decodeFailureHandler: TransportDecodeFailureHandler` |
| `MainDispatcher.receiveMessage` (and its overrides in the command, event and snapshot dispatchers) | implement `createMessageReceiver(subscription)`, for example `bus.receiver(subscription.copy(runtimeOwned = true))` |
| Starter `kafkaRecordDecodeFailureHandler()` returning `KafkaRecordDecodeFailureHandler` | the same bean method, returning `TransportDecodeFailureHandler`; an application bean of the old type becomes a `TransportDecodeFailureHandler` bean (`wow.kafka.receiver.decode-failure-strategy` is unchanged) |

Kafka-received messages are now read-only, as Redis and in-memory ones already were. Redis treats an entry whose message belongs to another stream as undecodable (`RedisRecordDecodeFailureReason.TOPIC_MISMATCH`) and leaves any undecodable entry pending.

### Other API Changes

- **WebFlux routes:** every materialized route's handler is wrapped in a `RouteIdentityHandlerFunction` (`@InternalWowApi`); code that inspects a route's `HandlerFunction` by its concrete type must unwrap it.
- **Error handling:** a custom `WebFluxErrorStrategy` reaches SSE error events and batch results through `toErrorInfo`, so override it alongside `toServerResponse`. A custom `RequestExceptionHandler` reports those errors through `handleInBody`; one that implements only `handle` no longer logs them.
- **OpenAPI:** `RouterSpecs.toRouteCatalog()` contracts carry no generated schemas unless `RouterSpecs.buildDocumentation()` or `mergeOpenAPIFromCatalog` ran first, and `OpenAPIAutoConfiguration.routerSpecs(…)` takes `ObjectProvider<RouteContributor>` and `OpenAPIProperties`. The OpenAPI document and the routes are unchanged.
- **API client:** `ReactiveRestCommandGateway` and `SyncRestCommandGateway` declare a new abstract `send(sendUri, headers, command)` that takes `CommandRequest.toRequestHeaders()`. A class that implements them by hand adds it; CoApi proxies are not affected.
- **Removed JSON record helpers:** `FlatEventStreamRecord`, `DelegatingMessageRecord`, `DelegatingNamedBoundedContextMessageRecord`, `toMessageRecord()` and `toBoundedContextMessageRecord()`; implement the record interface directly.
- **TCK:** `CommandGatewaySpec` has an overridable `requestIdExistenceChecker`, because a gateway without an event store no longer rejects on the precheck alone.

### Build: `wow-metadata` and Reproducible KSP Output

`wow-compiler` (the KSP processor) depends on a new `wow-metadata` module instead of `wow-core`. The metadata model (`me.ahoo.wow.configuration.*`, `me.ahoo.wow.naming.*`) moved there with unchanged packages, `wow-core` depends on it, and `wow-bom` aligns its version, so code that depends on `wow-core` sees the same classes. A build that reached `wow-core` only through `wow-compiler` declares it itself. Generated `@Generated` annotations no longer carry a date, so repeated builds produce byte-identical sources.

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
