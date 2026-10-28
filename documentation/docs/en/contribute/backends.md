---
title: Write a Backend
description: Add a storage backend to Wow, the ports of the SPI a store implements, how the host finds it, and the contract tests it must pass.
---

# Write a Backend

A backend is a module that implements Wow's storage ports on one technology. The built-in PostgreSQL and MongoDB backends are backends like any other: they implement the same ports, are found the same way, and pass the same contract tests. The ports are `@WowSpi`: a minor release may change them, and using them needs `@OptIn(WowSpi::class)` ([API Levels](../reference/api-levels)).

## The ports

A store holds a node's durable state. Its ports are in `:spi`:

| Port | Holds | Contract |
| --- | --- | --- |
| `EventLog` | A context's log: committed streams by partition and position, unique by version and by request; each entry's transport data as opaque bytes, stored and given back unchanged | `EventLogSpec` |
| `SnapshotStore` | The latest snapshot of each aggregate | `SnapshotStoreSpec` |
| `CheckpointStore` | Where each subscription stands, per partition | `CheckpointStoreSpec` |
| `LeaseStore` | Which node owns which partition | `LeaseStoreSpec` |
| `FailureLedger` | The parked aggregates | `FailureLedgerSpec` |
| `ReservationStore` | The reserved unique keys | `ReservationStoreSpec` |
| `TimerStore` | The commands scheduled for later | `TimerStoreSpec` |

A port's abstract members are what a backend must implement; a member with a default is an optional optimization whose default is correct, such as `EventLog.appendAll` and `SnapshotStore.saveAll`. Two capabilities are optional and offered explicitly: a store that holds a context's log and its snapshots in one database and can read an aggregate's snapshot together with its tail in one round trip returns a `SnapshotTailReader` from `Store.openSnapshotTails` (the node uses it for the aggregate types whose log and primary snapshots are both on that store), and a snapshot store that can rebuild its snapshots in place returns a `SnapshotRebuildStore` (the table claims, stale marks and rewrites of a state revision change) from `SnapshotStore.rebuilds`. Without them the node reads the snapshot and the tail apart, and leaves snapshots of another state revision until a commit replaces them. When a capability fails, the node falls back the same way, logs the first time and counts it (`wow.store.fallbacks`).

A store may hold logs, snapshots or both: an Elasticsearch store holds snapshots and never a log. Beside the stores, a `Sink` copies commits somewhere else (`SinkSpec`), a topic transport carries published streams and commands between contexts (`TopicTransportSpec`), and a query backend serves a model's queries (`QueryBackendSpec`, `EventQueryBackendSpec` and `DatasetQueryBackendSpec` in `:query-tck`).

## The provider

A backend gives the host a `StoreProvider`: the `type` a configuration names (`wow.stores.<name>.type`), what it needs from the application, whether it is available from the application's resources alone (what `wow.backend: auto` chooses by), and `open`, which returns a `Store` for one configured instance. `open` touches nothing in the database: the store opens each port on its first use, one log per context. Each `open*` returns the port with what creates its schema, `Opened.of(port, port::createSchema)` (or `Opened.of(port)` for one without), and the node creates the schemas when it starts, each once, so `wow.ddl` decides whether they are created, validated or left alone. `Ddl.apply(describe, exists, create, validate)` runs a structure's native checks as the mode says, and `EnsuredOnce` makes sure of each structure once, on its first use, however many ask at once.

The node probes each store it placed (`Store.probe`: a pooled connection's validity, a `ping`, a cluster's info) for its health: a store holding a log or a type's primary snapshots decides whether the node is ready, any other only shows how it is. Once the node stopped it closes each store once (`Store.closeStore`); the resources the host gave it are the host's to close.

What a store offers beyond its ports it gives as facets (`Store.facet`): its query side, `StoreQueries` (`:query`), when the backend serves the snapshot or `events` queries, and its pool, `StorePool`, when the pool says its connections. The node bounds the store's queries by its pool (a quarter of one the appends share, all of one of the queries' own) and reads the store's write pressure from it. A facet is a capability, never a way around a port.

Each query side hands out its bindings, a query backend and its storage's facts, through one shape, `QueryBindings` (`:query`): `binding(key)` for an aggregate type's snapshots or events or for a dataset, null for a key it does not serve. `CachedQueryBindings` creates each key's binding once, again when a dataset is redeclared, and drops it on `forget(key)`.

A binding's storage facts come from its `QueryStorageAdapter`. For each path of the model's `LogicalQuerySchema` its storage serves, the adapter reports the capabilities its native structures execute and where each is stored (`QueryValueBindings`), with the storage's model-wide `StorageSupport`; `QueryValueSchema.storageFamilies(capability)` says which storage families a value needs for a capability. It reports nothing it cannot run correctly: a capability it leaves out is refused at admission. The third-party store of `test/spi-consumer` reports its facts on the public vocabulary alone:

<!-- sample: test/spi-consumer/src/main/kotlin/me/ahoo/wow/consumer/AcmeQueries.kt#facts -->

```kotlin
override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts = QueryStorageFacts.build {
    logicalSchema.values.filterKeys { it.segments.isNotEmpty() }.forEach { (path, value) ->
        val stored = QueryFieldBindingTemplate.of(path, storageTypes = null)
        binding(path, QueryValueBindings.of(capabilitiesOf(value).associateWith { stored }, path, path))
    }
    storage(
        StorageSupport.of(
            aggregation = AggregationSupport.of(
                having = SupportMode.RESIDUAL,
                topN = SupportMode.RESIDUAL,
                denseFill = SupportMode.RESIDUAL,
            ),
        ),
    )
}
```

An aggregate's `x-wow-index` indexes reach the snapshot queries as a `QueryIndexPlan`: the core plans the declarations once against the model's schema, refusing a key the model does not know or the storage neither matches, ranges nor sorts, and gives each key's physical field, storage types and cardinality. A backend applies its own rules on top, such as PostgreSQL's single-valued keys or MongoDB's one multi-valued key, and creates or reports the indexes as `ddl` says:

<!-- sample: test/spi-consumer/src/main/kotlin/me/ahoo/wow/consumer/AcmeQueries.kt#indexes -->

```kotlin
override fun manageIndexes(type: AggregateType, plan: QueryIndexPlan): QueryIndexReport {
    val declared = plan.indexes.map { index ->
        // Acme's rule on top of the plan's: an index keys one value per record.
        index.keys.forEach { key ->
            require(key.cardinality == QueryCardinality.SINGLE) {
                "Index [${index.name}] has the key [${key.field}], which holds many values."
            }
        }
        index.name
    }.toSet()
    val existing = indexes.put(type, declared).orEmpty()
    return QueryIndexReport(created = (declared - existing).toList())
}
```

A provider declares every option its stores read (`StoreProvider.options`, each a `ProviderOption` built with `ProviderOption.string`, `duration`, `int` or `boolean`) and reads each through it (`StoreConfig.option(option)`), so the host binds and checks exactly those. An option declared with a default (a `ProviderOption.Defaulted`) reads as a value that is always there; one without may be missing, so it reads as nullable. An option of the type scope is set for every store of the type under `wow.<type>.<key>`, and each store may set it over that under `wow.stores.<name>.options.<key>`; one marked `perStore()`, such as the name of a resource bean, only on a store. A store setting its provider does not declare fails the start, naming the declared ones, and so does a `wow.<type>.*` key: the host learns the declared keys from the providers at startup. `StoreTck` checks that a store reads no option its provider does not declare. Wow's own backends list their keys in the host's configuration metadata; a third party may ship its own `META-INF/spring-configuration-metadata.json` so that IDEs complete them.

The host finds providers through Java's `ServiceLoader`, from the module's `META-INF/services/me.ahoo.wow.spi.store.StoreProvider`, and among the application's beans; a bean takes the place of a provider of the same type on the class path. A provider is stateless: each store instance gets its own `Store`, over the resources its configuration finds, a `DataSource` or a driver's client.

## Transport and Sink providers

A topic transport and a Sink are found the same way as stores. A `TransportProvider` (`:spi`, `me.ahoo.wow.spi.transport`) opens the broker that carries published streams, commands to other contexts and their receipts: its `open` returns a `TopicTransport`, the `TopicProducer`, `PartitionedSource` and `TopicAdmin` the node takes, and may warn about the node's command topics once it is ready. A `SinkProvider` (`me.ahoo.wow.spi.sink`) opens the Sinks of one destination: `open` returns a `StreamSinks`, one `Sink` per published stream, whose subscriptions are named `<processor>.<aggregate>`. The host finds them through `ServiceLoader`, from `META-INF/services/me.ahoo.wow.spi.transport.TransportProvider` and `META-INF/services/me.ahoo.wow.spi.sink.SinkProvider`, and among the application's beans; it builds nothing of a broker's or a warehouse's own. Wow's Kafka transport (`kafka`) and ClickHouse Sink (`clickhouse`) are providers like any other.

Until the configuration surface is final, a provider of type `<type>` reads the keys under `wow.<type>`, handed to it as options relative to that prefix (`bootstrap-servers`, `properties.linger.ms`), and says with `available` whether they configure it. A node runs at most one transport and every Sink that is configured. A setting a provider refuses with an `IllegalArgumentException` fails the startup naming the key.

## The contract tests

A backend's correctness is the contract, not its own tests. The TCK holds one abstract spec per port, and a backend proves itself by running the specs of the ports it implements against a fresh, empty store of its own. Wow's backends are held to this before they merge, and a backend outside the repository is held to the same specs.

### Run the TCK on your backend {#run-the-tck}

1. **Add the TCK to the tests.** The three artifacts are published and versioned by the BOM: `wow-tck` holds the store, Sink and transport specs and needs no Docker; `wow-query-tck` holds the query backends' specs; `wow-tck-containers` starts the shared containers with pinned images, for a backend whose tests need one of them.

   <!-- sample: fragment -->

   ```kotlin
   dependencies {
       testImplementation(platform("me.ahoo.wow:wow-bom:<version>"))
       testImplementation("me.ahoo.wow:wow-tck")
       testImplementation("me.ahoo.wow:wow-query-tck") // when it serves queries
       testImplementation("me.ahoo.wow:wow-tck-containers") // when it runs on one of Wow's containers
   }
   ```

2. **Run the whole store through `StoreTck`.** Give it the configuration of a store, and the kit opens it through your `StoreProvider` (found by `ServiceLoader` from its type, as the host finds it) and runs every port's spec against what the store opens: `EventLogSpec` when the store holds logs, `SnapshotStoreSpec` when it holds snapshots, and the control plane (timers, checkpoints, leases, the failure ledger, reservations) wherever it holds logs. A store that holds no log must refuse the log and the control plane with an `UnsupportedOperationException` that names it, and the kit checks that it does. A port the provider forgets to open, or opens wrong, fails here, where the per-port specs, fed the backend's own classes, cannot see it. The kit reads the time through the store (`StoreConfig.clock`), so pass `config.clock` to every port that reads the current time. The report names the backend's class and the port, `PostgresStoreTck > EventLog > ...`.

   <!-- sample: fragment -->

   ```kotlin
   class PostgresStoreTck : StoreTck() {
       override fun config(): StoreConfig = StoreConfig.of(
           name = "tck",
           type = PostgresStoreProvider.TYPE,
           resources = StoreResources.of(mapOf("dataSource" to PostgresFixture.dataSource)),
       )
   }
   ```

   Override `provider(type)` for a provider the class path does not list, and `refused(stream)` as in the log's spec.

3. **Extend one spec per port.** Each spec is an abstract JUnit 5 class: implement its abstract members, which hand the spec a fresh store, and nothing else. Its tests are final, so a backend cannot change or switch off one of them. The ports are `@WowSpi`, so the test class opts in with `@OptIn(WowSpi::class)`. The PostgreSQL log's test is the whole of what a log needs, with the two optional hooks that run the snapshot-tail and refused-stream tests:

   <!-- sample: fragment -->

   ```kotlin
   class PostgresEventLogTest : EventLogSpec() {
       override fun createLog(context: String, partitions: Int): EventLog = PostgresFixture.log(partitions, context)

       override fun createSnapshots(context: String): SnapshotStore =
           PostgresSnapshotStore(PostgresFixture.dataSource, context)

       /** A `\u0000` in a payload string: PostgreSQL's `jsonb` does not take it. */
       override fun refused(stream: EventStreamRecord): EventStreamRecord = stream.copyWith(
           events = listOf(EventRecord("x.aggregate.Named", 1, """{"name":"a\u0000b"}""")),
       )
   }
   ```

4. **Give each test its own storage, or keep them apart.** The log and snapshot specs pass each test a context of its own, so a backend that keeps a table or a collection per context isolates them; the checkpoint spec uses a group per test. The timer spec calls `createStore()` more than once in one test and expects each call to give a store over the same storage: return one store, or one per call over the same database.

5. **Run them in CI.** Wow's backends run the specs in the `integrationTest` source set against `WowContainers` (`postgres`, `mongo`, `kafka`, `clickHouse`, `elasticsearch`, each started once and shared; `newPostgres()` and the others give an unstarted one). A backend with containers of its own runs them the same way.

| Spec | Artifact | Implement | Optional |
| --- | --- | --- | --- |
| `StoreTck` | `wow-tck` | `config()`: a store of the backend's type | `provider(type)`, `refused(stream)` |
| `EventLogSpec` | `wow-tck` | `createLog(context, partitions)` | `createSnapshots(context)`, `refused(stream)` |
| `SnapshotStoreSpec` | `wow-tck` | `createStore(context)` | |
| `CheckpointStoreSpec` | `wow-tck` | `createStore()` | |
| `LeaseStoreSpec` | `wow-tck` | `createStore(clock)`: the spec moves the clock | |
| `FailureLedgerSpec` | `wow-tck` | `createLedger()` | |
| `ReservationStoreSpec` | `wow-tck` | `createStore(clock)` | |
| `TimerStoreSpec` | `wow-tck` | `createStore()` | `context`, `logPartitions` |
| `SinkSpec` | `wow-tck` | `createSink()`, `written(ref)`: the versions the destination holds | `aggregateType`, `record(ref, version)`, `visibleWithin` |
| `TopicTransportSpec` | `wow-tck` | `producer`, `source`, `newTopic(partitions)` | `readTimeout` |
| `QueryBackendSpec` | `wow-query-tck` | `store(snapshots)`, `binding(type)` | `semanticDivergences` |
| `EventQueryBackendSpec` | `wow-query-tck` | `store(streams)`, `binding(type)` | |
| `DatasetQueryBackendSpec` | `wow-query-tck` | `createDatasetBindings()`, `createTable(table)` | `semanticDivergences` |

Run `StoreTck` for every store type the backend provides, and the per-port specs for what only they reach: a hook of the backend's own. Of the other specs, run those of the ports the backend implements, not all of them. Among Wow's own, PostgreSQL and MongoDB run the seven store specs, `QueryBackendSpec` and `EventQueryBackendSpec`; Elasticsearch runs `SnapshotStoreSpec`, `SinkSpec` and the two query specs; Kafka runs `TopicTransportSpec`; ClickHouse runs `SinkSpec` and `DatasetQueryBackendSpec`.

### Capabilities and skipped tests {#capabilities}

A backend declares what it can do on the ports themselves, and the spec skips, through a JUnit assumption that names the reason, the tests of what it does not:

- **A store's optional capabilities.** `Store.openSnapshotTails` (a `SnapshotTailReader`) runs the snapshot-tail tests through it, over the snapshots `createSnapshots` returns; without it the spec reads the snapshot and the tail apart, as the node does, and without `createSnapshots` it skips them. `EventLog.appendsAcrossPartitions` runs the test of one `appendAll` across partitions. `SnapshotStore.rebuilds` (a `SnapshotRebuildStore`) runs the rebuild tests.
- **What a store refuses.** `refused` returns a stream the backend's storage cannot hold, such as a NUL in a PostgreSQL `jsonb` string, and the spec expects its append to be rejected (`AppendOutcome.Rejected`) and, in an `appendAll`, to fail alone; without it the spec skips that test.
- **A query backend's storage.** The binding's storage facts say which features the storage serves natively, partly or not at all: keyset pages, unbounded streams, percentiles, temporal aggregation and the others. The spec skips the tests of a feature the storage does not serve, and says which fact declined it and its value: `Skipped: the storage declares storage.paging.keyset = NONE (keyset pages).` A known divergence from the filter semantics is listed by its case id in `semanticDivergences`, and its test is skipped as a known divergence rather than hidden.

### From the 9.x TCK {#from-9x}

9.x had message buses and dispatchers with specs of their own. V10 has no buses: subscriptions read the log, and traffic between contexts goes through the topic transport and the Sinks. A backend ported from 9.x runs these instead:

| 9.x spec | V10 |
| --- | --- |
| `MessageBusSpec`, `CommandBusSpec` | `TopicTransportSpec`, for the transport between contexts. Commands inside a service go over the cluster channel, which is not a port and has no spec |
| `DomainEventBusSpec`, `StateEventBusSpec` | Removed: subscriptions read the log, which `EventLogSpec` and `CheckpointStoreSpec` cover; between contexts, `SinkSpec` and `TopicTransportSpec` |
| `CommandGatewaySpec`, `CommandDispatcherSpec`, `ProjectionDispatcherSpec` | Removed: the gateway and the dispatchers are inside the runtime, not ports; their stores are covered by the specs above |
| `EventStoreSpec` | `EventLogSpec` |
| `SnapshotStoreSpec` | `SnapshotStoreSpec` (`createStore(context)`) |
| `PrepareKeySpec` | `ReservationStoreSpec` |
| `SnapshotQueryBackendSpec`, `EventStreamQueryBackendSpec`, `DatasetQueryBackendSpec` | `QueryBackendSpec`, `EventQueryBackendSpec`, `DatasetQueryBackendSpec` |
| `StateAggregateRepositorySpec`, `StateAggregateFactorySpec`, `SnapshotStrategySpec`, `DomainEventStreamSpec` | Removed: kernel internals, not ports |
| The Testcontainers fixtures | `wow-tck-containers` (`WowContainers`), without Redis |

`CheckpointStoreSpec`, `LeaseStoreSpec`, `FailureLedgerSpec`, `TimerStoreSpec` and `SinkSpec` are new in V10: a 9.x backend implemented none of these ports.

## The rules

- A backend depends on `:spi`, and on `:query` when it serves queries; it never depends on another backend or on the runtime. `:architecture` holds this, and the new module joins `ALLOWED_PROJECT_DEPENDENCIES`.
- It records the format of every structure it creates and refuses one of another format, so a node never writes a layout it does not understand. It decides what a `ddl` mode does through `Ddl.ensure` or `Ddl.apply`, so each mode means the same in every store; `:architecture` holds that its code names no mode otherwise.
- It is a published module: it keeps an ABI dump, a README that links to the site, and a page under Operate for its operators.
- It reports its failures as the SPI's typed exceptions, which the runtime classifies itself; a backend registers no failure classifier. A store or a broker it cannot reach is a `StorageUnavailableException` or a `TransportUnavailableException`, systemic: a subscription pauses and retries, and parks nothing. A value it refuses for good is a `StorageRefusedException` or a `TransportRefusedException`, on which the aggregate parks at once. A statement it stops at the caller's timeout is a `StorageTimeoutException`, which the query core answers as `wow.query.timeout`. Any other failure stays the driver's own exception.

The PostgreSQL backend, `backends/postgres`, is the reference to read alongside its tests.
