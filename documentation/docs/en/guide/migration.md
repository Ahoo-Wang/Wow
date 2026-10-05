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

9.3.0 removes declarations that only kept older bytecode or 9.2 extension code linking. Recompile libraries built against 9.2 or earlier before upgrading; source that already compiled without deprecation warnings needs no change.

| Removed | Use instead |
|---|---|
| `MessageBus.receive(subscription)` | `receiver(subscription).openedMessages()`; a bus implements `receiver`, which is now abstract |
| `MessageBus.runtimeReceiver(subscription)` | `receiver(subscription.copy(runtimeOwned = true))` |
| `MessageSubscription` constructors and `copy` without `runtimeOwned` (JVM only) | the constructors and `copy` with `runtimeOwned` (it defaults to `false`) |
| Redis bus constructors without `retentionOptions` (JVM only) | the primary constructors (`retentionOptions` defaults to `RedisStreamRetentionOptions.DEFAULT`) |
| 9.1 constructors of `BindingError`, `AggregationGroup.Terms` and `AggregationGroup.Histogram` (JVM only) | the primary constructors |
| `ServerRequest.getTenantId`, `getTenantIdOrDefault`, `getOwnerId`, `getSpaceId`, `getAggregateId` (all overloads) | `DefaultCommandBuilderExtractor` / `DefaultQueryRequestScope`, or `RouteIdentity.of(request).binding(…)` |
| `CoSecCommandBuilderExtractor`, `CoSecQueryRequestScope` | `CoSecIdentityHeaders.ALIASES`, registered by `CoSecAutoConfiguration` |
| `Flux<AggregateId>.toBatchResult(afterId)`, `ResendStateEventHandler.handle(afterId, limit)` | `toBatchResult(afterId, request, exceptionHandler)`, `resend(afterId, limit)` |
| Non-bean `WebFluxAutoConfiguration.commandMessageExtractor`, `queryRequestScope`, `commandRouterFunction` and `pointReadAdmission` overloads, `CoSecAutoConfiguration.coSecCommandBuilderExtractor` / `coSecQueryRequestScope`, the three-argument `OpenAPIAutoConfiguration.routerSpecs` | the `@Bean` methods of the same name |

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

### BI Script Route Needs `wow-bi` (9.3.0)

`wow-webflux` and the Starter's `webflux-support` / `openapi-support` capabilities no longer bring `wow-bi` (and the ClickHouse client). An application that serves `POST /wow/bi/script` adds `wow-bi`, or requests the Starter's `bi-support` capability; with it on the classpath the route, its OpenAPI operation and schemas, its error codes and the `wow.bi.script.*` properties are unchanged. Without it the route is absent. The BI route classes moved to the Starter:

| Removed | Use instead |
|---|---|
| `me.ahoo.wow.webflux.route.global.GenerateBIScriptHandlerFunction` / `GenerateBIScriptHandlerFunctionFactory` | wired by `me.ahoo.wow.spring.boot.starter.bi.BiAutoConfiguration` (internal) |
| `me.ahoo.wow.spring.boot.starter.webflux.bi.BiDeploymentInspectorAutoConfiguration` | `me.ahoo.wow.spring.boot.starter.bi.BiAutoConfiguration` |
| `GenerateBIScriptRouteContributor` in `DefaultRouteContributors.all()` | a `RouteContributor` bean (the Starter registers it when `wow-bi` is present); outside Spring pass `DefaultRouteContributors.all() + GenerateBIScriptRouteContributor` to `RouterSpecs` |

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
