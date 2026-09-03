---
title: Business Intelligence
description: Generate and operate ClickHouse read models from Wow command and state-event streams.
---

# Business Intelligence

## Traditional Architecture VS Event Sourcing

In a traditional reporting pipeline, an ETL job reads mutable business tables and has to infer what changed. Wow BI
instead projects immutable command and state-event Kafka topics into ClickHouse. The event store remains the source of
truth; ClickHouse is a rebuildable analytical read model.

![Event Sourcing VS Traditional Architecture](/images/eventstore/eventsourcing.svg)

That ownership boundary has operational consequences:

- application metadata defines the desired schema and view graph;
- Kafka retention and offsets define what can be replayed;
- ClickHouse object comments and the ownership registry identify objects managed by the current BI deployment;
- BI rows must be reconciled with the event/state source before a cutover is accepted.

Do not write domain state back from BI tables, and do not treat a successful SQL generation as proof that ClickHouse
is current.

![Business Intelligence](/images/bi/bi.svg)

## Generate and Retrieve ETL Scripts

### Structured Result API

`BiScriptGenerator` returns SQL plus an explicit operation and diagnostics:

```kotlin
val options = BiScriptOptions(
    database = "bi_db",
    consumerDatabase = "bi_db_consumer",
    topology = ClickHouseTopology.Standalone,
    consumerGroupNamespace = "orders-blue",
)
val generator = BiScriptGenerator(options)
val preparation = generator.prepare(namedAggregates)
val result: Mono<BiScriptResult> = inspector
    .inspect(options, BiScriptOperation.Deploy, preparation)
    .map { inspection ->
        generator.generate(preparation, BiScriptOperation.Deploy, inspection)
    }
```

`BiScriptResult` exposes `script`, `diagnostics`, `operation`, and `destructive`; statement boundaries are internal so
an executor must still run the rendered SQL in order and stop on the first failure. `Reset(true)` is the only
destructive operation. It requires an available deployment inspection and explicit confirmation that a new Kafka
consumer generation may replay from earliest.

`consumerGroupNamespace` is mandatory whenever aggregate consumers are generated. It separates an application's BI
ownership scope from another deployment using the same Kafka cluster.

Diagnostics are a stable structured review surface:

| Code | Meaning |
|---|---|
| `RAW_JSON_FALLBACK` | A value is preserved as scoped raw JSON instead of a typed projection |
| `MAX_DEPTH_REACHED` | Expansion stopped at `maxExpansionDepth` |
| `INSPECTION_UNAVAILABLE` | Desired SQL was generated without an authoritative catalog observation |
| `ORPHANED_DATA_TABLE` | Managed data is retained because ownership/reconciliation cannot safely remove it |
| `CLUSTER_INTERNAL_REPLICATION_REQUIRED` | Cluster-side `internal_replication` must be configured externally |
| `COMPUTED_OBJECT_DRIFT` | A view/materialized-view definition differs and reconciliation is planned |

`UnsupportedTypeStrategy.RAW_JSON` is the default; `FAIL` rejects unsupported or unverifiable shapes instead of
emitting fallback columns.

### HTTP Route

When `wow.bi.script.enabled=true`, WebFlux exposes `POST /wow/bi/script`. The request body is required; `{}` means
`DEPLOY` with server-side options. Ask for JSON when diagnostics and the destructive flag are needed:

```bash
curl --fail-with-body \
  -X POST http://localhost:8080/wow/bi/script \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"operation":"DEPLOY"}'
```

The JSON response is `{ "script", "destructive", "diagnostics" }`; every response also includes
`Wow-BI-Diagnostic-Count`. `Accept: application/sql` returns only SQL and therefore must not be the approval surface
for a destructive operation.

Request fields may lower `maxExpansionDepth` and override non-null generation options, but the server owns
`consumerGroupNamespace` and offset storage. An authoritative inspector rejects request overrides of `database`,
`consumerDatabase`, or `topology` so inspection and generation cannot observe different physical scopes. `RESET`
requires `replayFromEarliestConfirmed=true`; that field is invalid for `DEPLOY`.

The default `NoOpBiDeploymentInspector` returns explicit `Unavailable`. It supports initial/offline `DEPLOY` preview
with a diagnostic, but cannot clean stale objects, recover a prior consumer identity, or execute `RESET`. Production
reconciliation requires `wow.bi.script.inspector.type=CLICKHOUSE` and ClickHouse endpoints, or a custom authoritative
`BiDeploymentInspector`. Protect this operational route with the deployment gateway.

## Generated SQL Contract

The current renderer owns protocol `3`, layout `7`. Each managed ClickHouse object carries a `wow-bi:` JSON comment
with deployment/configuration/topology fingerprints, object kind, aggregate owner where applicable, and consumer
identity. The `__wow_bi_deployment` anchor records deployment phase and registry revision. Unknown protocol/layout or
an inconsistent registry fails closed; object names alone never establish ownership.

### Kafka Offset Lifecycle

`KafkaOffsetStorage.BROKER` is the default. ClickHouse Kafka engine consumer groups include the deployment's consumer
identity. A normal `DEPLOY` preserves the identity observed in a stable current layout; a confirmed `RESET` creates or
continues a reset generation that starts from earliest.

`KafkaOffsetStorage.KEEPER` adds `kafka_keeper_path`, `kafka_replica_name`, and
`allow_experimental_kafka_offsets_storage_in_keeper=1`. Keeper availability, replication, Kafka retention, and
earliest-offset behavior are external prerequisites, not properties the generator can prove.

### Deployment Topologies

`ClickHouseTopology.Standalone` creates `ReplacingMergeTree` stores directly. Cluster mode creates
`ReplicatedReplacingMergeTree` local stores and `Distributed` facades, using `ON CLUSTER` and the configured
installation/cluster macros. The operator owns the matching ClickHouse cluster configuration and
`internal_replication` setting.

Changing database, consumer database, consumer-group namespace, or topology changes a durable contract. Inspect and
plan it as a deployment/cutover, not a harmless SQL regeneration.

### Aggregate Commands

For each aggregate, the command topic feeds a Kafka queue table, a consumer materialized view, a physical command
store, and a public `..._command` view. Stable columns include message/aggregate identity, owner/space/request fields,
aggregate version, create/void flags, command body, and create time. The public view reads the store with `FINAL`.

### Full State Events

The state topic feeds the same queue → materialized view → store pattern. The public `..._state` view exposes complete
state-event records; `..._state_event` expands the event-body array with a one-based event sequence and event metadata.
The stored `state` JSON is retained for authoritative recovery.

### Latest State

The `..._state_last_store` is populated from the state store and ordered by tenant/aggregate identity so the public
`..._state_last` view yields the latest version with `FINAL`. It is a derived latest-state read model, not the source
event stream. Reconcile aggregate count, maximum version, deleted state, and representative replay before using it for
business cutover.

### Root Expansion View

The expansion planner reads the configured Jackson wire shape and creates a root view over `state_last`. Scalar and
verified structural properties become typed columns. Every root row also retains `__state` and uses the empty RFC
6901 pointer in `__path`, so typed projection never removes the recovery source.

### Child Expansion View

Each verified collection of objects becomes a child view. `arrayJoin` emits one row per element while preserving
parent identity, `__state`, zero-based `__index`, and an RFC 6901 `__path` such as `/orders/2/lines/5`. Property
segments escape `~` as `~0` and `/` as `~1`.

### Nullable Types and Raw Values

Nullability is structural: nullable scalar → `Nullable(T)`, nullable element → `Array(Nullable(T))`, nullable map
value → `Map(String, Nullable(T))`. When typed extraction cannot distinguish missing, explicit `null`, and a nullable
container, the view adds a scoped `__raw__<property>` convenience column.

## Structural Types and Lossless Semantics

### Nullability Propagation Rules

- A nullable object ancestor makes its typed descendants nullable.
- Only the nullable ancestor receives the raw companion; descendants do not duplicate it.
- A nullable object element keeps the current raw element in its child view.
- Unannotated Java reference types are potentially nullable; proved Kotlin/Java non-null contracts remain non-null.
- A whole-value fallback is already raw and receives no second raw column.

### Authoritative State Recovery

`__state` is the lexical authority because it projects the stored state string without parsing and reserializing it.
Use `__path` to source-slice the required subtree or token. Root rows use the empty path; child rows carry the complete
pointer. `__raw__*` is convenient for queries but is not lexical-authoritative.

The prefixes/names `__raw__`, `__state`, `__path`, `__index`, and `__cursor__` are reserved by the generator.

### Scoped Raw Convenience Values

`JSONExtractRaw` can distinguish missing (`''`), explicit null (`'null'`), and empty array/object (`'[]'`/`'{}'`) in
many query cases. ClickHouse may normalize numeric spelling, so exact recovery still comes from `__state` plus
`__path`.

### Unsupported Types

Object-valued maps, non-String or nullable map keys, unresolved generics, and platform-specific objects cannot be
projected safely. `RAW_JSON` emits a diagnostic and preserves a scoped raw value; `FAIL` stops generation. Reaching the
configured maximum expansion depth follows the same recovery rule with `MAX_DEPTH_REACHED`.

### Opaque Jackson Shapes

Recursive expansion is allowed only when the configured Wow `JsonSerializer` proves that the declared object shape
matches its JSON object. Polymorphic/abstract/sealed objects, `@JsonValue`, `@JsonUnwrapped`, `@JsonAnyGetter`, custom
serializers/converters, and otherwise unverifiable shapes remain opaque. They are kept raw or rejected by `FAIL`.

### Lossless Scalar Mappings

| Wire value | Representative JVM values | ClickHouse projection |
|---|---|---|
| String | `String`, `Char`, ordinary enum | `String` |
| Integer | integer primitives, `Year` | exact signed integer / `Int32` |
| Boolean | `Boolean` | `Bool` |
| Number | `Float`, `Double` | `Float32`, `Float64` |
| UUID string | `UUID` | `UUID` |
| ISO/string time | Java time/date/duration values | `String` |
| Arbitrary precision number | `BigDecimal` | scoped raw plus `__state` recovery |

A configured serializer that changes one of these wire shapes makes the property opaque unless the resolver can prove
the new mapping.

See [BI Deployment and Recovery](./bi-operations) before executing generated SQL.

<!-- Sources: wow-bi BiScriptGenerator/Options/PreparationPlanner, renderer package, expansion planner/type package,
BiDeploymentInspection, and expected_bi_*_script.sql; WebFlux GenerateBIScriptHandlerFunction -->
