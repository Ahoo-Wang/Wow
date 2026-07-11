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
        unsupportedTypeStrategy = UnsupportedTypeStrategy.RAW_JSON,
    )
).generate(aggregates)

val sql: String = result.script
val diagnostics: List<BiScriptDiagnostic> = result.diagnostics
```

The public contract consists of seven types: `BiScriptGenerator`, `BiScriptOptions`, `UnsupportedTypeStrategy`, `BiScriptResult`, `BiScriptDiagnostic`, `BiScriptDiagnosticCode`, and `BiScriptMappingDecision`. Planning, rendering, type mapping, and executable-statement models are internal.

`BiScriptResult` contains:

| Field | Meaning |
|-------|---------|
| `script` | Complete ClickHouse deployment SQL with global, clear, command, state-event, latest-state, and expansion sections in order. |
| `diagnostics` | An immutable diagnostic list with stable aggregate and property-path ordering. |

Each `BiScriptDiagnostic` contains `code`, `aggregate`, `path`, `sourceType`, `decision`, and `message`. The current diagnostic protocol contains only:

| `code` | `decision` | Meaning |
|--------|------------|---------|
| `RAW_JSON_FALLBACK` | `RAW_JSON` | An unsupported property is preserved as one complete raw JSON value. |
| `MAX_DEPTH_REACHED` | `MAX_DEPTH_RAW_JSON` | The maximum expansion depth was reached, so the whole value is preserved as raw JSON. |

The default `unsupportedTypeStrategy` is `RAW_JSON`. With `FAIL`, an unsupported property stops generation immediately, and the exception message includes the aggregate, property path, and source type. Object-valued maps use the same strategy; fallback preserves one whole raw JSON value.

### HTTP Route

The Spring WebFlux route uses the same `BiScriptOptions`:

```shell
curl -X GET 'http://localhost:8080/wow/bi/script' \
  -H 'accept: application/sql'
```

A successful response is always `200` with `Content-Type: application/sql`, and its body contains only `result.script`. Each diagnostic is emitted as a WARN log and is never mixed into the SQL. See [Configuration](./configuration#bi-script-configuration) for properties and precedence.

## Generated SQL Contract

The following fragments show only stable structure. Actual database names, cluster, Kafka address, topic, and aggregate table names come from `BiScriptOptions` and aggregate metadata.

Generated BI SQL requires ClickHouse 24.8 LTS or later. The module integration suite pins the minimum line to a 24.8 image.

### Aggregate Commands

The command local table includes tenant, owner, space, request, version, and command-body metadata. The Kafka materialized view extracts the same semantics from message JSON:

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

The state local table is partitioned monthly by `create_time` and ordered by `(aggregate_id, version)`.

### Latest State

The latest-state table receives all columns from the distributed state table and is partitioned by first-event time:

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
WITH simpleJSONExtractRaw("__source"."state", 'address') AS "address"
SELECT JSONExtract("address", 'city', 'String') AS "address__city",
       JSONExtract("__source"."state", 'id', 'String') AS "id",
       JSONExtractArrayRaw("__source"."state", 'items') AS "items",
       "__source"."owner_id" AS "__owner_id",
       "__source"."space_id" AS "__space_id",
       "__source"."tags" AS "__tags"
FROM "bi_db"."example_order_state_last" AS "__source";
```

### Child Expansion View

An object collection produces a child view. `arrayJoin` expands each object element into one row while inheriting parent columns and metadata:

```sql
CREATE VIEW IF NOT EXISTS "bi_db"."example_order_state_last_root_items" ON CLUSTER '{cluster}' AS
WITH arrayJoin(JSONExtractArrayRaw("__source"."state", 'items')) AS "items"
SELECT JSONExtract("items", 'id', 'String') AS "items__id",
       JSONExtract("items", 'quantity', 'Int32') AS "items__quantity",
       "__source"."aggregate_id" AS "__aggregate_id",
       "__source"."version" AS "__version"
FROM "bi_db"."example_order_state_last" AS "__source";
```

### Nullable Types and Raw Values

Types are generated from structural property nullability. `Nullable` may appear at scalar, array-element, and Map-value levels:

```sql
JSONExtract("__source"."state", 'name', 'Nullable(String)') AS "name",
simpleJSONExtractRaw("__source"."state", 'name') AS "__raw__name",
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
- A whole-value fallback target is already the authoritative raw value and does not get a duplicate `__raw__*` companion.

Unannotated Java reference types are treated as potentially nullable; explicit Kotlin and Java non-null contracts remain non-null.

### Reserved `__raw__*` Namespace

The `__raw__` prefix is reserved by the generator. Domain-property serialized names must not occupy it. Generation fails on a collision with raw companions or `__*` metadata columns instead of silently overwriting a column. Raw extraction uses `simpleJSONExtractRaw` against compact JSON produced by Wow `JsonSerializer`; this preserves the original numeric token instead of normalizing arbitrary precision through a floating-point representation.

Raw columns distinguish states that typed extraction cannot always distinguish:

| JSON input | `simpleJSONExtractRaw` result | Typed extraction |
|------------|-------------------------|------------------|
| Missing property | Empty string `""` | Nullable scalar becomes SQL `NULL`; array/Map may become empty. |
| Explicit `null` | String `"null"` | Nullable scalar becomes SQL `NULL`; array/Map may become empty. |
| Empty array / object | String `"[]"` / `"{}"` | Empty array / Map. |

The `__raw__*` columns for nullable scalars, arrays, maps, and objects are therefore the lossless channel for distinguishing missing, explicit null, and empty values.

### Unsupported Types

Object-valued maps, Map keys that are non-String or potentially nullable, platform objects, and unresolved generic shapes cannot be mapped safely to a direct ClickHouse type. The default strategy stores the whole property in its normal target column through `simpleJSONExtractRaw` and emits a diagnostic; `FAIL` rejects generation. Reaching `maxExpansionDepth` also preserves the whole raw value, with a separate depth diagnostic.

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
| `BigDecimal` | Arbitrary-precision number | Complete `simpleJSONExtractRaw` value |
| Kotlin `Duration` | Resolver-provided `Long` wire value | `Int64` |
| Enum with a non-string wire format | Unverified scalar | Complete raw value or `FAIL` |
