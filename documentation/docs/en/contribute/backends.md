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
| `EventLog` | A context's log: committed streams by partition and position, unique by version and by request | `EventLogSpec` |
| `SnapshotStore` | The latest snapshot of each aggregate | `SnapshotStoreSpec` |
| `CheckpointStore` | Where each subscription stands, per partition | `CheckpointStoreSpec` |
| `LeaseStore` | Which node owns which partition | `LeaseStoreSpec` |
| `FailureLedger` | The parked aggregates | `FailureLedgerSpec` |
| `ReservationStore` | The reserved unique keys | `ReservationStoreSpec` |
| `TimerStore` | The commands scheduled for later | `TimerStoreSpec` |

A store may hold logs, snapshots or both: an Elasticsearch store holds snapshots and never a log. Beside the stores, a `Sink` copies commits somewhere else (`SinkSpec`), a topic transport carries published streams and commands between contexts (`TopicTransportSpec`), and a query backend serves a model's queries (`QueryBackendSpec`, `EventQueryBackendSpec` and `DatasetQueryBackendSpec` in `:query-tck`).

## The provider

A backend gives the host a `StoreProvider`: the `type` a configuration names (`wow.stores.<name>.type`), what it needs from the application, whether it is available from the application's resources alone (what `wow.backend: auto` chooses by), and `open`, which returns a `Store` for one configured instance. `open` touches nothing in the database: the store opens each port on its first use, one log per context, and remembers what creates its schemas, so `wow.ddl` decides whether they are created, validated or left alone.

The host finds providers through Java's `ServiceLoader`, from the module's `META-INF/services/me.ahoo.wow.spi.store.StoreProvider`, and among the application's beans; a bean takes the place of a provider of the same type on the class path. A provider is stateless: each store instance gets its own `Store`, over the resources its configuration finds, a `DataSource` or a driver's client.

## The contract tests

A backend's correctness is the contract. Its container tests extend each spec of the ports it implements and supply a fresh, empty store:

<!-- sample: fragment -->

```kotlin
class PostgresEventLogTest : EventLogSpec() {
    override fun createLog(context: String, partitions: Int): EventLog = PostgresFixture.log(partitions, context)
}
```

Each test of a spec runs on a context of its own, so a backend that keeps a table or a collection per context isolates them. A query backend declares the features its storage may decline, keyset pages or percentiles say, and the spec skips their tests; a known divergence from the filter semantics is listed by id rather than hidden. `:tck-containers` starts the shared containers with pinned images, and the backend's module runs the suites in `src/integrationTest`.

## The rules

- A backend depends on `:spi`, and on `:query` when it serves queries; it never depends on another backend or on the runtime. `:architecture` holds this, and the new module joins `ALLOWED_PROJECT_DEPENDENCIES`.
- It records the format of every structure it creates and refuses one of another format, so a node never writes a layout it does not understand.
- It is a published module: it keeps an ABI dump, a README that links to the site, and a page under Operate for its operators.
- A store it cannot reach is reported as `StorageUnavailableException`, which the node treats as systemic: a subscription pauses and retries, and parks nothing.

The PostgreSQL backend, `backends/postgres`, is the reference to read alongside its tests.
