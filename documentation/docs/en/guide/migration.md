---
title: Migration Guide
description: Guide for migrating from traditional architecture to the Wow framework and upgrading between versions.
---

# Migration Guide

This guide helps you migrate from traditional architecture to the Wow framework, as well as upgrade between different versions.

## Version Upgrade Guide

### Upgrade Steps

1. **Backup Data**: Backup event store and snapshot data before upgrading
2. **Read Changelog**: Check [Release Notes](https://github.com/Ahoo-Wang/Wow/releases)
3. **Update Dependency Version**: Modify build.gradle.kts or pom.xml
4. **Run Tests**: Ensure all tests pass
5. **Gradual Rollout**: Gradually upgrade production environment

### Dependency Version Update

::: code-group
```kotlin [Gradle(Kotlin)]
// Update wow version
implementation("me.ahoo.wow:wow-spring-boot-starter:new-version")
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-spring-boot-starter</artifactId>
    <version>new-version</version>
</dependency>
```
:::

### Breaking Changes Check

Before upgrading, check the following:

1. **API Changes**: Check for interface signature changes
2. **Configuration Changes**: Check for configuration property changes
3. **Metadata Changes**: Regenerate metadata files

## Unified Runtime Orchestration

This release replaces independent dispatcher launchers with one one-shot
`WowRuntime`. The runtime prepares every component before opening message
processing, tracks global activity, and stops components in reverse order under
one shared deadline. This is an intentional lifecycle extension break; event,
snapshot, and message formats are unchanged.

Apply the following source migrations:

1. Subclasses of `MainDispatcher`, `AggregateDispatcher`, or
   `CompositeEventDispatcher` must move lifecycle customization from the public
   methods to the corresponding protected hooks:

   | Previous override | Replacement hook |
   | --- | --- |
   | `prepare(RuntimeContext)` | `prepareManaged(RuntimeContext)` |
   | `start()` | `startManaged()` |
   | `stopGracefully()` | `stopManagedGracefully()` |
   | `forceStop()` | `forceStopManaged()` |

   The public lifecycle methods are now final templates. Recompile every
   dispatcher subclass; previously compiled subclasses that override those
   methods are not binary compatible.
2. Remove `MessageDispatcherLauncher` beans and launcher injections from
   Starter applications. The deprecated launcher classes remain available only
   for direct `wow-spring` compatibility; registering one beside the Starter's
   canonical `WowRuntimeLifecycle` fails application-context refresh. Do not
   replace, rename, or duplicate the canonical `wowRuntime` and
   `wowRuntimeLifecycle` beans; add participants through
   `WowRuntimeComponent`.
3. A custom `MessageDispatcher` should implement `RuntimeComponent`. A legacy
   dispatcher may be adapted only when it implements a real, prompt
   `ForceStoppable` cancellation path. Other non-dispatcher Spring participants
   must implement `WowRuntimeComponent`. Custom components are exclusively
   owned by one runtime instance. Create one `RuntimeOwnership` handle and retain
   it for the component's complete lifetime. Ownership claims and their
   commit/rollback transaction are runtime-internal; public shared ownership is
   no longer supported.

   ```kotlin
   class CustomRuntimeComponent : WowRuntimeComponent {
       override val runtimeOwnership = RuntimeOwnership()

       override fun prepare(runtimeContext: RuntimeContext) {
           runtimeContext.onAdmissionClose(::closeIntake)
       }

       override fun start() = openIntake()
       override fun stopGracefully(): Mono<Void> = drainAndClose()
       override fun forceStop() = closeIntake()
   }
   ```

   Acquire a `RuntimeActivity` with `RuntimeContext.tryAcquire()` before
   accepting each asynchronous operation and close it only when the complete
   chain terminates. Use `onAdmissionClose` for the graceful intake barrier and
   `reportFailure` for fatal pipeline errors. Hard force-stop may cancel a queued
   intake callback, so `forceStop` must close intake synchronously.
4. Runtime-owned Spring beans must be singletons, and their declared bean return
   type must expose `MessageDispatcher`, `WowRuntimeComponent`, or the concrete
   implementation. Remove Spring `Lifecycle`/`SmartLifecycle`,
   `DisposableBean`, `@PreDestroy`, and explicit destroy methods from these
   beans: `WowRuntime` is their only lifecycle owner. Scoped proxies and
   non-static AOP target sources are unsupported. Static proxies are resolved to
   their stable target, so lifecycle advice on the proxy is not invoked. Bean
   constructors, factory methods, and `@PostConstruct` must remain inert;
   acquire runtime-owned resources only from `prepare` or `start`.
5. If the application replaces Spring's bean named `lifecycleProcessor`, it
   must remain a `DefaultLifecycleProcessor`; Wow configures the runtime phase
   timeout on that processor. Runtime components share one Spring ordering
   sequence: startup follows `@Order`, and shutdown reverses it. A custom
   ingress `SmartLifecycle` must use a phase greater than
   `WOW_RUNTIME_PHASE`, so ingress starts after runtime readiness and stops
   before the runtime.
6. For a `FactoryBean`, Spring still destroys the factory itself. Its runtime
   product is stopped only by `WowRuntime`; product `close` or `@PreDestroy`
   must not be a second cleanup path. Starter registry, ownership validator, and
   lifecycle-processor customizer types are infrastructure, not extension SPIs.

Review the shutdown configuration and behavior:

- `wow.shutdown-timeout` is now the deadline for quiescing and stopping the
  complete runtime, rather than a separate allowance for each dispatcher.
- `wow.shutdown-quiet-period` is new and defaults to `1s`. It must be
  non-negative and strictly shorter than `wow.shutdown-timeout`; both durations
  must fit in signed 64-bit nanoseconds.
- The runtime, `AutoRegistrar`, dispatcher resources, and batch coordinators
  that reach terminal shutdown are one-shot. Recreate the Spring
  `ApplicationContext` instead of stopping and restarting it.
- Runtime termination signals may complete with the original pipeline error.
  Each subscriber reserves bounded asynchronous-delivery capacity when it
  subscribes; an over-capacity subscriber receives
  `RejectedExecutionException` immediately on that subscription thread.
  Admitted callbacks never run on the runtime completion thread, but must still
  return promptly or offload blocking work. In Starter applications,
  `WowRuntimeLifecycle` exclusively claims a separate bounded control lane
  before startup. Public observer saturation cannot starve Spring stop
  completion; an unexpected fatal runtime termination closes the application
  context.

Before deployment, compile all custom dispatcher subclasses and run
application-context startup and graceful-shutdown tests with the production
timeout values. No data migration is required. To roll back, stop the new
application context completely and deploy the previous binaries and launcher
configuration; do not try to restart a context whose runtime has terminated.

## Versioned Snapshot Checkpoint Removal

The versioned snapshot checkpoint capability introduced in v8.9.0 has been removed without a compatibility layer.
`VersionedSnapshotStore`, `VersionIntervalCheckpointStrategy`, `CompositeSnapshotStrategy`, their metrics and tracing
decorators, and `SnapshotCheckpointProperties` no longer exist. The `wow.eventsourcing.snapshot.checkpoint.*`
properties are ignored, and the `wow.snapshot.checkpoint.*` metrics and checkpoint spans are no longer emitted.
There is no replacement API; applications should use `SnapshotStore`, which stores and loads only the latest snapshot.

MongoDB `*_snapshot_checkpoint` collections are no longer read, written, scanned, or automatically deleted. Back up
event and snapshot data before upgrading, stop all old-version writers, and remove those collections only after
confirming they are no longer needed. Rollback requires restoring the old runtime and retaining its checkpoint data;
mixed-version deployment is unsupported.

## Atomic SnapshotStore Saves

`SnapshotStore.save()` keeps the same JVM signature and snapshot formats, but its
storage contract is stronger: each aggregate must use one atomic compare-and-write
operation. A candidate whose aggregate version is greater than or equal to the
stored version replaces the complete snapshot; a lower candidate completes
successfully without writing. Equal-version replacement is intentional so the
snapshot-regeneration routes can repair a stale payload.

Custom `SnapshotStore` implementations must use a backend CAS, conditional update,
transaction, or equivalent atomic primitive. A client-side `load()` followed by an
unconditional write is not conformant. Materialize the candidate once and derive the
comparison version from that same payload. Stop and drain all old writers before
relying on this guarantee: old MongoDB or Redis writers can still regress a newer
snapshot, and an old Elasticsearch writer does not perform equal-version replacement.
No data rewrite is required. Rollback restores the old save behavior, so do not run
old and new writers concurrently.

For `wow-mongo`, the guarded update uses MongoDB MQL expressions that require
MongoDB 5.2 or later; the integration suite verifies MongoDB 6.0.6. Upgrade the
MongoDB server before deploying this runtime when the existing server is older.

## Redis EventStore Canonical v2 Layout (introduced in v8.9.0)

When upgrading from v8.6.x or v8.8.x to v8.9.0, treat Redis persistence as a hard storage-format cutover. Redis
EventStore, Redis SnapshotStore, and Redis PrepareKey read and write canonical v2 keys only. There is no legacy
fallback, dual write, or built-in migrator, and old runtimes cannot read new v2 writes. The new EventStore also
enforces that `AggregateId.id` is unique within a named aggregate across all tenants.

The Spring Boot starter checks the exact sentinel keys created by successful writes in the published v8.6 and v8.8
EventStore layouts. It checks local aggregates resolved to the auto-configured `RedisEventStore`, supports Redis
Cluster without runtime `SCAN`, and blocks startup when incompatible data is found. It does not cover direct-library
usage, independently constructed custom stores, retired aggregate metadata, or snapshot-only Redis routes. A legacy
snapshot has no aggregate-independent exact sentinel. Canonical v2 ignores legacy snapshot keys. A missing v2
snapshot causes aggregate loading to replay events, but normal loading does not persist a rebuilt snapshot
automatically.

The exact-key guard is not a substitute for an offline data audit. A historical alias change, key eviction, or a
manually deleted or corrupted legacy index can hide the sentinel while orphaned streams remain. The resolved context
alias (the configured alias, or `contextName` when no alias is configured) and aggregate name form the persistent v2
key scope. The migration manifest must pin every historical source alias to the target resolved alias. Changing the
resolved alias or aggregate name after a write requires a separate offline key migration.

Use an offline cutover:

1. Stop traffic and every old-version writer, drain in-flight appends to zero, and create a consistent Redis backup
   together with event-count and version baselines. Do not use a mixed-version rolling deployment.
2. Inventory all legacy event ZSETs, v8.6 shared request SETs, v8.8 per-stream request SETs, v8.8 bucketed ID ZSETs,
   and legacy snapshot and PrepareKey hashes in every logical database on every Cluster primary. Record source key,
   Redis type, cardinality, checksum, and target mapping. Use identity embedded in event or snapshot JSON as the
   authority; an ambiguous historical key is only a locator.
3. Audit each named aggregate for duplicate `AggregateId.id` values across tenants. Resolve every collision before
   migration; canonical v2 intentionally cannot represent two owners of one ID.
4. Use an empty v2 target scope on the first run. For disposable data, remove only the inventoried legacy keys from
   the target or use an empty dedicated database. Never use `FLUSHDB` on a database shared with message-bus or
   application data. Keep the complete source dataset immutable for rollback.
5. Run a separately reviewed offline migrator. Its durable manifest must record source key, target keys, source and
   target checksums, status, and last completed batch. Resume may reuse a target only when manifest and checksum
   match; otherwise fail without overwriting. Copy operations must be idempotent, and partial target data must not be
   accepted without manifest-backed re-verification.
6. Preserve every event ZSET member and score, and verify identity consistency plus contiguous score/version order.
   Treat committed event JSON as authoritative for v2 request-ID SETs. For v8.6, compare the shared SET with
   `union(event.requestId)` in both directions and report shared-only and event-only differences separately; never fan
   it out to streams. For v8.8, compute the symmetric difference between each source per-stream SET and that stream's
   event request IDs. A non-empty difference fails migration unless an explicit reviewed disposition is recorded.
7. Rebuild every non-empty aggregate-ID index in the 128-bucket space. The bucket is
   `aggregateId.id.hashCode().mod(128)` using Java/Kotlin UTF-16 `String.hashCode`; keys and members must use the exact
   canonical v2 codec. The runtime does not perform this conversion.
8. Verify ordered member-and-score checksums, first/last versions, request-ID equality, the complete ID index,
   aggregate-ID scan results, and representative state replay. A failed run must retain its manifest and last verified
   cursor, then either clean the partial target or resume from that cursor; the application must not start meanwhile.
9. After full verification, an in-place migration must remove or move every legacy key in the recorded inventory.
   Delete sentinel keys last, rerun inventory, and require zero legacy keys. With a separate target database, keep the
   complete source dataset read-only through the rollback window.
10. Start one new instance against the target and run isolated-ID read/write smoke tests. Explicitly regenerate
    snapshots, then verify snapshot counts and versions before switching traffic and scaling out. Use the single-ID
    regenerate route from the complete inventory. The batch route may be treated as exhaustive only when the audited
    ID domain is strictly above `AggregateIdScanner.FIRST_ID`; otherwise it can omit lower IDs.

Rollback is a coordinated application-and-data operation. Before production v2 writes, reconnect the untouched
legacy dataset and old runtime. After any production v2 write, first stop traffic and v2 writers, then reverse-migrate
or replay those writes before restarting the old runtime; restoring only the cutover backup loses every later v2
write. Prefer a separate target database or namespace.

The mandatory exact-key check is an internal startup invariant. It is intentionally neither optional nor exposed as
a compatibility or migration setting.

Source, JVM binary, and behavioral compatibility are intentionally broken for Redis layout internals. Removed APIs
include `AggregateKeyConverter`, `RedisWrappedKey`, `RedisSnapshotRepository`, `EventStreamKeyConverter`,
`DefaultSnapshotKeyConverter`, `PrepareKeyConverter`, and `RedisEventStore.SCRIPT_EVENT_STEAM_APPEND`; the
`redisSnapshotRepository` bean alias and custom snapshot-key converter constructor are also removed. The new
`SCRIPT_EVENT_STREAM_APPEND` is internal, with no public replacement. Canonical converter outputs changed, PrepareKey
now includes its `name`, and v2 rejects empty aggregate/prepare IDs and unpaired UTF-16 surrogates. Application code
should use `EventStore`, `SnapshotStore`, and `PrepareKey`; reviewed offline tooling must independently implement and
verify the documented v2 codec.

## Mongo Ownership Guard

This upgrade keeps aggregate-name-only Mongo collection names, but adds a durable
`wow_database_metadata` ownership marker. The supported deployment layout is one bounded context per MongoDB
database.

Before rollout:

1. Inspect every configured event-stream, snapshot, and prepare database. Check all `*_event_stream`, `*_snapshot`,
   and `prepare_*` collections.
2. Confirm that each database belongs to only one `wow.context-name`; a mixed database must be split before upgrade.
3. Upgrade the database's real owner first. The first upgraded instance scans legacy aggregate collections before
   atomically claiming the marker. Legacy `prepare_*` records contain no context metadata, so a prepare-only database
   is claimed by the first upgraded context and must be audited before rollout.
4. Audit existing managed indexes. Missing indexes are created, but incompatible key order, uniqueness, TTL,
   partial-filter, collation, sparse, or hidden options block startup and require a controlled migration.

Do not edit the marker to bypass a context mismatch. Move or remove the old data, then remove the marker only when
the database is intentionally reassigned.

## Migrating from Traditional Architecture

### Migration Strategy

#### Gradual Migration

We recommend a gradual migration strategy, progressively migrating functional modules to event sourcing architecture:

```mermaid
flowchart LR
    subgraph Legacy["Traditional Architecture"]
        LDB[(Relational Database)]
        LS[Legacy Service]
    end
    
    subgraph Wow["Wow Framework"]
        ES[(Event Store)]
        WS[Wow Service]
    end
    
    LS -->|Publish Events| WS
    WS -->|Sync Data| LDB

```

#### Migration Steps

1. **Identify Bounded Contexts**: Determine business modules to migrate
2. **Design Domain Model**: Define aggregate roots, commands, and events
3. **Implement Dual Writing**: Write to both old and new systems
4. **Verify Consistency**: Ensure data consistency
5. **Switch Read/Write**: Gradually switch to new system

### Data Migration

#### Historical Data Import

For scenarios requiring historical data preservation, it is recommended to define migration commands:

```kotlin
// 1. Define Migration Command
@CreateAggregate
data class MigrateOrder(
    val orderId: String,
    val customerId: String,
    val items: List<OrderItem>,
    val createdAt: Long
)

// 2. Handle Migration Command in Aggregate
@AggregateRoot
class Order(private val state: OrderState) {
    @OnCommand
    fun onMigrate(command: MigrateOrder): OrderCreated {
        return OrderCreated(
            orderId = command.orderId,
            customerId = command.customerId,
            items = command.items,
            createdAt = command.createdAt
        )
    }
}

// 3. Send Migration Command
fun migrateHistoricalData(legacyOrders: List<LegacyOrder>) {
    legacyOrders.forEach { order ->
        val command = MigrateOrder(
            orderId = order.id,
            customerId = order.customerId,
            items = order.items.map { /* convert */ },
            createdAt = order.createdAt
        )
        commandGateway.send(command).block()
    }
}
```

### Code Migration

#### From CRUD to Command Pattern

**Traditional CRUD Code**:

```kotlin
// Traditional service
@Service
class OrderService(private val orderRepository: OrderRepository) {
    
    fun createOrder(request: CreateOrderRequest): Order {
        val order = Order(
            id = UUID.randomUUID().toString(),
            customerId = request.customerId,
            items = request.items,
            status = OrderStatus.CREATED
        )
        return orderRepository.save(order)
    }
    
    fun updateOrderStatus(orderId: String, status: OrderStatus) {
        val order = orderRepository.findById(orderId)
        order.status = status
        orderRepository.save(order)
    }
}
```

**Migrated Wow Code**:

```kotlin
// Command definitions
@CreateAggregate
data class CreateOrder(
    val customerId: String,
    val items: List<OrderItem>
)

@CommandRoute
data class UpdateOrderStatus(
    @AggregateId val id: String,
    val status: OrderStatus
)

// Aggregate root
@AggregateRoot
class Order(private val state: OrderState) {
    
    @OnCommand
    fun onCreate(command: CreateOrder): OrderCreated {
        return OrderCreated(
            customerId = command.customerId,
            items = command.items
        )
    }
    
    @OnCommand
    fun onUpdateStatus(command: UpdateOrderStatus): OrderStatusUpdated {
        return OrderStatusUpdated(command.status)
    }
}

// State aggregate root
class OrderState : Identifier {
    lateinit var id: String
    lateinit var customerId: String
    var items: List<OrderItem> = emptyList()
    var status: OrderStatus = OrderStatus.CREATED
    
    fun onSourcing(event: OrderCreated) {
        this.customerId = event.customerId
        this.items = event.items
    }
    
    fun onSourcing(event: OrderStatusUpdated) {
        this.status = event.status
    }
}
```

#### From Direct Queries to Query Snapshots

**Traditional Query Code**:

```kotlin
@Repository
interface OrderRepository : JpaRepository<Order, String> {
    fun findByCustomerId(customerId: String): List<Order>
    fun findByStatus(status: OrderStatus): List<Order>
}
```

**Migrated Query Code**:

Refer to [Query Service](query.md)

```kotlin
class OrderService(
    private val queryService: SnapshotQueryService<OrderState>
) {
    fun getById(id: String): Mono<OrderState> {
        return singleQuery {
            condition {
                id(id)
            }
        }.query(queryService).toState().throwNotFoundIfEmpty()
    }
}
```

## Compatibility Notes

### Data Format Compatibility

The Wow framework uses JSON serialization for events and snapshot data, ensuring good forward compatibility:

- **Adding Fields**: New fields will be ignored (backward compatible)
- **Removing Fields**: Uses default values (needs handling)
- **Changing Field Types**: Requires event upgrader

### Event Upgrades

Use the `revision` attribute of the `@Event` annotation for event version control:

```kotlin
@Event(revision = "1.0")
data class OrderCreatedV1(
    val orderId: String,
    val items: List<OrderItem>
)

@Event(revision = "2.0")
data class OrderCreated(
    val orderId: String,
    val items: List<OrderItem>,
    val customerId: String // New field
)
```

### Message Format Compatibility

Ensure message format compatibility:

1. **Adding Fields**: Safe, uses default values
2. **Removing Fields**: Need to ensure consumers can handle
3. **Renaming Fields**: Not compatible, requires version control

## Known Issues

### Version-Specific Issues

Please check [GitHub Issues](https://github.com/Ahoo-Wang/Wow/issues) for the latest known issues list.

### Common Migration Issues

1. **Event Replay Order**: Ensure events are appended in version order
2. **Timestamp Handling**: Preserve original timestamps
3. **ID Generation**: Maintain consistent ID format

## Migration Checklist

- [ ] Backup existing data
- [ ] Update dependency version
- [ ] Check breaking changes
- [ ] Update configuration files
- [ ] Regenerate metadata
- [ ] Run unit tests
- [ ] Run integration tests
- [ ] Gradual rollout verification
- [ ] Full rollout
- [ ] Monitoring verification

## Rollback Plan

If migration fails, follow these rollback steps:

1. Stop new service
2. Restore old service
3. Verify data consistency
4. Analyze failure cause
5. Fix issues and retry
