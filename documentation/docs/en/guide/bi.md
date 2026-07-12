---
title: Business Intelligence
description: Wow exposes real-time aggregate state events and commands as business intelligence data sources.
---

# Business Intelligence

## Traditional Architecture VS Event Sourcing

<center>

![Event Sourcing VS Traditional Architecture](../../public/images/eventstore/eventsourcing.svg)
</center>

A traditional real-time ETL pipeline usually follows `DB -> CDC -> Process -> DB`. CDC records data changes, so the analytics side must reconstruct business meaning from those changes. Wow publishes commands and state events with business semantics and generates ClickHouse synchronization and expansion SQL, shortening the real-time analytics path.

- Aggregate command (`Command`): a command submitted by a user.
- State event (`StateEvent`): the complete aggregate-state change history and its related events.
- Latest state event (`LastStateEvent`): the latest state for each aggregate root.
- Snapshot expansion view: a relational view expanded from one-to-one and one-to-many structures inside an aggregate.

![Business Intelligence](../../public/images/bi/bi.svg)

## Generate and Retrieve ETL Scripts

### Structured Result API

Kotlin callers obtain SQL and diagnostics through `BiScriptGenerator`:

```kotlin
val result = BiScriptGenerator(
    BiScriptOptions(
        topology = ClickHouseTopology.Standalone,
        unsupportedTypeStrategy = UnsupportedTypeStrategy.RAW_JSON,
    )
).generate(aggregates)

val sql: String = result.script
val diagnostics: List<BiScriptDiagnostic> = result.diagnostics
```

The public contract consists of eight top-level types: `BiScriptGenerator`, `BiScriptOptions`, `ClickHouseTopology`, `UnsupportedTypeStrategy`, `BiScriptResult`, `BiScriptDiagnostic`, `BiScriptDiagnosticCode`, and `BiScriptMappingDecision`. `ClickHouseTopology` exposes the `Standalone` and `Cluster` variants. `BiScriptGenerator.generate(...)` is the single generation entry point; planning, rendering, type mapping, and executable-statement models are internal.

`BiScriptResult` contains:

| Field | Meaning |
|-------|---------|
| `script` | Complete ClickHouse deployment SQL with global, clear, command, state-event, latest-state, and expansion sections in order. |
| `diagnostics` | An immutable diagnostic list with stable aggregate and property-path ordering. |

Each `BiScriptDiagnostic` contains `code`, `aggregate`, `path`, `sourceType`, `decision`, and `message`. The current diagnostic protocol contains only:

| `code` | `decision` | Meaning |
|--------|------------|---------|
| `RAW_JSON_FALLBACK` | `RAW_JSON` | An unsupported property uses a scoped JSON convenience projection and authoritative `__state` recovery. |
| `MAX_DEPTH_REACHED` | `MAX_DEPTH_RAW_JSON` | The maximum expansion depth was reached, so the same recovery contract is used. |

The default `unsupportedTypeStrategy` is `RAW_JSON`. With `FAIL`, an unsupported property stops generation immediately, and the exception message includes the aggregate, property path, and source type. Object-valued maps use the same strategy; fallback remains exactly recoverable through `__state` and the current recovery path.

### HTTP Route

The Spring WebFlux route uses the same `BiScriptOptions`:

```shell
curl -X GET 'http://localhost:8080/wow/bi/script' \
  -H 'accept: application/sql'
```

A successful response is always `200` with `Content-Type: application/sql`, and its body contains only `result.script`. Each diagnostic is emitted as a WARN log and is never mixed into the SQL. See [Configuration](./configuration#bi-script-configuration) for properties and precedence.

## Generated SQL Contract

The following fragments show only stable structure. Actual database names, deployment topology, Kafka address, topic, and aggregate table names come from `BiScriptOptions` and aggregate metadata.

Generated BI SQL requires ClickHouse 24.8 LTS or later. The module integration suite pins the minimum line to a 24.8 image.

### Deployment Topologies

`BiScriptOptions.topology` selects one of two physical DDL graphs:

| Topology | Physical tables | Logical access | DDL scope |
|----------|-----------------|----------------|-----------|
| `ClickHouseTopology.Standalone` | `command`, `state`, and `state_last` use `MergeTree` / `ReplacingMergeTree` directly | The physical tables are the logical tables; no `Distributed` facade is generated | No `ON CLUSTER`, replicated engine, `_local` table, or replication path |
| `ClickHouseTopology.Cluster(...)` | `command_local`, `state_local`, and `state_last_local` use replicated engines | `command`, `state`, and `state_last` are `Distributed` facades over the local tables | Every database, table, materialized view, and expansion view uses `ON CLUSTER` |

Standalone command storage is a single physical table:

```sql
CREATE TABLE IF NOT EXISTS "bi_db"."example_order_command"
(
    "id" String,
    "aggregate_id" String,
    "create_time" DateTime('Asia/Shanghai')
) ENGINE = MergeTree
  PARTITION BY toYYYYMM("create_time")
  ORDER BY "id";
```

The clustered graph retains a replicated local table plus a distributed facade:

```sql
CREATE TABLE IF NOT EXISTS "bi_db"."example_order_command_local" ON CLUSTER '{cluster}'
(
    "id" String,
    "aggregate_id" String,
    "create_time" DateTime('Asia/Shanghai')
) ENGINE = ReplicatedMergeTree(
    '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}')
  PARTITION BY toYYYYMM("create_time")
  ORDER BY "id";

CREATE TABLE IF NOT EXISTS "bi_db"."example_order_command" ON CLUSTER '{cluster}'
AS "bi_db"."example_order_command_local"
ENGINE = Distributed('{cluster}', "bi_db",
                     'example_order_command_local', sipHash64("aggregate_id"));
```

### Aggregate Commands

The command table includes tenant, owner, space, request, version, and command-body metadata. In cluster mode the physical table uses the `_local` suffix shown above. The Kafka materialized view extracts the same semantics from message JSON:

```sql
CREATE TABLE IF NOT EXISTS "bi_db"."example_order_command_local" ON CLUSTER '{cluster}'
(
    "id" String,
    "aggregate_id" String,
    "tenant_id" String,
    "owner_id" String,
    "space_id" String,
    "aggregate_version" Nullable(UInt32),
    "body" String,
    "create_time" DateTime('Asia/Shanghai')
) ENGINE = ReplicatedMergeTree(
    '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}')
  PARTITION BY toYYYYMM("create_time")
  ORDER BY "id";

SELECT JSONExtractString("data", 'ownerId') AS "owner_id",
       JSONExtractString("data", 'spaceId') AS "space_id",
       JSONExtractString("data", 'body') AS "body";
```

### Full State Events

The state table stores the complete state JSON in `state`, the event JSON array in `body`, and structured tags in a Map. The event view then expands `body` in event order:

```sql
"state" String,
"body" Array(String),
"tags" Map(String, Array(String))

JSONExtractArrayRaw("data", 'body') AS "body",
JSONExtract("data", 'tags', 'Map(String, Array(String))') AS "tags"

WITH arrayJoin(arrayZip(arrayEnumerate("body"), "body")) AS "events"
SELECT "events".1 AS "event_sequence",
       JSONExtract("events".2, 'body', 'String') AS "event_body";
```

The state table is partitioned monthly by `create_time` and ordered by `(aggregate_id, version)`. Standalone mode renders `ReplacingMergeTree("version")` directly on `example_order_state`; cluster mode renders `ReplicatedReplacingMergeTree(..., "version")` on `example_order_state_local` and adds the distributed `example_order_state` facade.

### Latest State

The latest-state table receives all columns from the state table and is partitioned by first-event time. Standalone mode uses the following physical DDL without clustered clauses:

```sql
CREATE TABLE IF NOT EXISTS "bi_db"."example_order_state_last"
(
    "aggregate_id" String,
    "version" UInt32,
    "first_event_time" DateTime('Asia/Shanghai')
) ENGINE = ReplacingMergeTree("version")
  PARTITION BY toYYYYMM("first_event_time")
  ORDER BY "aggregate_id";

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_db_consumer"."example_order_state_last_consumer"
TO "bi_db"."example_order_state_last"
AS SELECT * FROM "bi_db"."example_order_state";
```

Cluster mode retains the replicated local table and targets its distributed facade:

```sql
CREATE TABLE IF NOT EXISTS "bi_db"."example_order_state_last_local" ON CLUSTER '{cluster}'
(
    "aggregate_id" String,
    "version" UInt32,
    "first_event_time" DateTime('Asia/Shanghai')
) ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}',
    '{replica}', "version")
PARTITION BY toYYYYMM("first_event_time")
ORDER BY "aggregate_id";

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_db_consumer"."example_order_state_last_consumer"
TO "bi_db"."example_order_state_last"
AS SELECT * FROM "bi_db"."example_order_state";
```

### Root Expansion View

The root view expands one-to-one objects into columns and inherits state-event metadata through `__*` columns. Physical input columns are qualified through the fixed `__source` table alias so domain output aliases cannot shadow metadata columns:

```sql
CREATE VIEW IF NOT EXISTS "bi_db"."example_order_state_last_root" ON CLUSTER '{cluster}' AS
WITH JSONExtractRaw("__source"."state", 'address') AS "address"
SELECT JSONExtract("address", 'city', 'String') AS "address__city",
       JSONExtract("__source"."state", 'id', 'String') AS "id",
       JSONExtractArrayRaw("__source"."state", 'items') AS "items",
       "__source"."state" AS "__state",
       '' AS "__path",
       "__source"."owner_id" AS "__owner_id",
       "__source"."space_id" AS "__space_id",
       "__source"."tags" AS "__tags"
FROM "bi_db"."example_order_state_last" AS "__source";
```

### Child Expansion View

An object collection produces a child view. `arrayJoin` expands each object element into one row while inheriting parent columns and metadata:

```sql
CREATE VIEW IF NOT EXISTS "bi_db"."example_order_state_last_root_items" ON CLUSTER '{cluster}' AS
WITH arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'items')),
                        JSONExtractArrayRaw("__source"."state", 'items'))) AS "__cursor__items",
     tupleElement("__cursor__items", 2) AS "items"
SELECT JSONExtract("items", 'id', 'String') AS "items__id",
       JSONExtract("items", 'quantity', 'Int32') AS "items__quantity",
       "__source"."state" AS "__state",
       toUInt64(tupleElement("__cursor__items", 1) - 1) AS "__index",
       concat('/items/', toString(tupleElement("__cursor__items", 1) - 1)) AS "__path",
       "__source"."aggregate_id" AS "__aggregate_id",
       "__source"."version" AS "__version"
FROM "bi_db"."example_order_state_last" AS "__source";
```

### Nullable Types and Raw Values

Types are generated from structural property nullability. `Nullable` may appear at scalar, array-element, and Map-value levels:

```sql
JSONExtract("__source"."state", 'name', 'Nullable(String)') AS "name",
JSONExtractRaw("__source"."state", 'name') AS "__raw__name",
JSONExtract("__source"."state", 'scores', 'Array(Nullable(Int32))') AS "scores",
JSONExtract("__source"."state", 'ratings', 'Map(String, Nullable(Int32))') AS "ratings"
```

## Structural Types and Lossless Semantics

### Nullability Propagation Rules

- A nullable scalar maps to `Nullable(T)`.
- A nullable collection element maps to `Array(Nullable(T))`; a nullable Map value maps to `Map(String, Nullable(T))`.
- When the collection or Map property itself is nullable, the generator keeps the typed column and adds a `__raw__<target>` companion.
- When an object property itself is nullable, all typed descendants become nullable and only the nullable ancestor gets one raw companion; descendants do not get duplicate raw columns.
- When an object collection element is nullable, the child view keeps the current `arrayJoin` element in `__raw__<target>`, and descendant columns use nullable extraction types.
- A whole-value fallback target is already the scoped JSON convenience value and does not get a duplicate `__raw__*` companion.

Unannotated Java reference types are treated as potentially nullable; explicit Kotlin and Java non-null contracts remain non-null.

### Authoritative State Recovery

Every expansion view projects `state_last.state` directly as `__state`. This column is the only lexical authority: it is never parsed or reserialized by ClickHouse. Root views expose the empty RFC 6901 pointer as `__path`. Collection child views additionally expose the current zero-based `__index` and a complete `__path`, such as `/orders/2/lines/5`. Property segments encode `~` as `~0` and `/` as `~1`.

Consumers that need an exact child token or subtree must source-slice `__state` at `__path`. Parsing and reserializing JSON is not lexical recovery.

The `__raw__` prefix, `__state`, `__path`, `__index`, and the internal `__cursor__` prefix are reserved by the generator. Domain-property serialized names must not occupy these targets.

### Scoped Raw Convenience Values

`__raw__*` and fallback columns use scoped `JSONExtractRaw`. They are useful for querying and for distinguishing missing, explicit null, and empty values, but ClickHouse may normalize number spelling inside them. They are never lexical-authoritative.

Raw columns distinguish states that typed extraction cannot always distinguish:

| JSON input | Scoped `JSONExtractRaw` result | Typed extraction |
|------------|-------------------------|------------------|
| Missing property | Empty string `""` | Nullable scalar becomes SQL `NULL`; array/Map may become empty. |
| Explicit `null` | String `"null"` | Nullable scalar becomes SQL `NULL`; array/Map may become empty. |
| Empty array / object | String `"[]"` / `"{}"` | Empty array / Map. |

The scoped columns distinguish these structural states; `__state` and `__path` remain the exact recovery channel.

### Unsupported Types

Object-valued maps, Map keys that are non-String or potentially nullable, platform objects, and unresolved generic shapes cannot be mapped safely to a direct ClickHouse type. The default strategy emits a scoped `JSONExtractRaw` convenience column and a diagnostic; the exact value remains recoverable through `__state` and the current recovery path. `FAIL` rejects generation. Reaching `maxExpansionDepth` follows the same recovery contract with a separate depth diagnostic.

### Opaque Jackson Shapes

Recursive expansion is enabled only when the configured Wow `JsonSerializer` proves that the declared object and its serialized JSON object have the same property shape. Polymorphic, abstract, sealed, `@JsonValue`, `@JsonUnwrapped`, `@JsonAnyGetter`, custom-serialized, converted, and otherwise unverifiable objects are opaque. An opaque property is preserved as one complete raw JSON value or rejected by `FAIL`; an opaque root preserves the complete `state`. Collections and maps receive typed projections only with Jackson's built-in container serializers and verified element, key, and value mappings.

### Lossless Scalar Mappings

Scalar mappings must agree with both the configured Jackson wire format and the target ClickHouse type. A mismatch follows the same raw/fail policy as any other opaque value.

| JVM value | Jackson wire value | ClickHouse projection |
|-----------|--------------------|-----------------------|
| `String` | String | `String` |
| Integer primitives/boxed values | Integer | Exact signed integer type |
| `Boolean` | Boolean | `Bool` |
| `Char` and ordinary string enums | String | `String` |
| `Float` / `Double` | Number, or Jackson's strings for non-finite values | `Float32` / `Float64` |
| `UUID` | UUID-formatted string | `UUID` |
| `Duration`, `Date`, `java.sql.Date`, `Instant`, and other `java.time` values | ISO/string representation | `String` |
| `Year` | Signed integer | `Int32` |
| `BigDecimal` | Arbitrary-precision number | Scoped `JSONExtractRaw` convenience plus authoritative `__state` recovery |
| Kotlin `Duration` | Resolver-provided `Long` wire value | `Int64` |
| Enum with a non-string wire format | Unverified scalar | Scoped raw convenience plus `__state` recovery, or `FAIL` |
