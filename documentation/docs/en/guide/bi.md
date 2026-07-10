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

The root view expands one-to-one objects into columns and inherits state-event metadata through `__*` columns:

```sql
CREATE VIEW IF NOT EXISTS "bi_db"."example_order_state_last_root" ON CLUSTER '{cluster}' AS
WITH JSONExtractRaw("state", 'address') AS "address"
SELECT JSONExtract("address", 'city', 'String') AS "address__city",
       JSONExtract("state", 'id', 'String') AS "id",
       JSONExtractArrayRaw("state", 'items') AS "items",
       "owner_id" AS "__owner_id",
       "space_id" AS "__space_id",
       "tags" AS "__tags"
FROM "bi_db"."example_order_state_last";
```

### Child Expansion View

An object collection produces a child view. `arrayJoin` expands each object element into one row while inheriting parent columns and metadata:

```sql
CREATE VIEW IF NOT EXISTS "bi_db"."example_order_state_last_root_items" ON CLUSTER '{cluster}' AS
WITH arrayJoin(JSONExtractArrayRaw("state", 'items')) AS "items"
SELECT JSONExtract("items", 'id', 'String') AS "items__id",
       JSONExtract("items", 'quantity', 'Int32') AS "items__quantity",
       "aggregate_id" AS "__aggregate_id",
       "version" AS "__version"
FROM "bi_db"."example_order_state_last";
```

### Nullable Types and Raw Values

Types are generated from structural property nullability. `Nullable` may appear at scalar, array-element, and Map-value levels:

```sql
JSONExtract("state", 'name', 'Nullable(String)') AS "name",
JSONExtractRaw("state", 'name') AS "__raw__name",
JSONExtract("state", 'scores', 'Array(Nullable(Int32))') AS "scores",
JSONExtract("state", 'ratings', 'Map(String, Nullable(Int32))') AS "ratings"
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

The `__raw__` prefix is reserved by the generator. Domain-property serialized names must not occupy it. Generation fails on a collision with raw companions or `__*` metadata columns instead of silently overwriting a column.

Raw columns distinguish states that typed extraction cannot always distinguish:

| JSON input | `JSONExtractRaw` result | Typed extraction |
|------------|-------------------------|------------------|
| Missing property | Empty string `""` | Nullable scalar becomes SQL `NULL`; array/Map may become empty. |
| Explicit `null` | String `"null"` | Nullable scalar becomes SQL `NULL`; array/Map may become empty. |
| Empty array / object | String `"[]"` / `"{}"` | Empty array / Map. |

The `__raw__*` columns for nullable scalars, arrays, maps, and objects are therefore the lossless channel for distinguishing missing, explicit null, and empty values.

### Unsupported Types

Object-valued maps, Map keys that are non-String or potentially nullable, platform objects, and unresolved generic shapes cannot be mapped safely to a direct ClickHouse type. The default strategy stores the whole property in its normal target column through `JSONExtractRaw` and emits a diagnostic; `FAIL` rejects generation. Reaching `maxExpansionDepth` also preserves the whole raw value, with a separate depth diagnostic.

## Breaking Migration

### Removed API and Configuration Migration Table

This table is the only compatibility reference for old names. No compatibility adapter is provided.

| Removed | Replacement |
|---------|-------------|
| `ScriptEngine` / `ScriptTemplateEngine` | `BiScriptGenerator` + `BiScriptOptions` |
| `StateExpansionScriptGenerator` | Internal structural planning through `BiScriptGenerator` |
| `SqlBuilder` / `SqlTypeMapping` / `TableNaming` / `expansion.column.*` | No direct replacement; SQL construction, type mapping, and column models are handled by the generator's internal structural planner/renderer |
| `BiTableNaming` and planner, plan, resolver, renderer, syntax, and ClickHouse type implementation types | No supported public replacement; these are now implementation details and callers should depend only on the `BiScriptGenerator` result protocol |
| `BiScriptOptions.validate()` | Validation runs when constructing `BiScriptOptions`; no explicit `validate()` call is required |
| `BiScriptDiagnostic.severity` / `Severity` | Removed; returned diagnostics are warnings, while strict failures throw directly |
| `OBJECT_MAP_FALLBACK` / `UNSUPPORTED_TYPE_FALLBACK` | `RAW_JSON_FALLBACK`, with `sourceType` and `decision` describing the mapping decision |
| `BiScriptRouteOptions` and route enums | `BiScriptOptions` / `UnsupportedTypeStrategy` |
| WebFlux String/default constructors | Constructors accepting `BiScriptOptions` |
| Starter `BiScriptUnsupportedTypeStrategy` / `BiScriptObjectMapStrategy` | Bind `UnsupportedTypeStrategy` directly; object maps no longer have a separate strategy |
| `GlobalRouteModule(KafkaProperties?)` and its old dual construction path | Use Starter auto-configuration and `wow.bi.script.*`; `GlobalRouteModule` is now internal |
| `STRING_WITH_DIAGNOSTIC` | `RAW_JSON` |
| `ObjectMapStrategy` / `object-map-strategy` / `STRING_VALUE_WITH_DIAGNOSTIC` | Unified `unsupported-type-strategy` with `RAW_JSON` / `FAIL` |

### Impact Scope

This change modifies generated snapshot-expansion column types, nullability, and raw companion columns. It affects generated expansion views and their downstream queries, views, and BI datasets. Command tables, source state tables, and existing event data do not require data migration merely because view types changed.

`CREATE VIEW IF NOT EXISTS` does not alter an existing view. Upgrades must drop and recreate generated expansion views; rerunning only the create statements is insufficient.

### Rollout

1. Back up current expansion-view definitions and affected downstream query and dataset definitions.
2. Generate SQL with the new code and configuration; review types, `__raw__*` columns, databases, topics, and diagnostics.
3. Drop only generated expansion views, children before parents, using the same `ON CLUSTER` scope as the generated SQL; do not run the complete destructive `clear` section.
4. Recreate views in generated order with the same `ON CLUSTER` scope, then validate column types on every node, missing/null/empty distinctions, and downstream queries.
5. Deploy consumers that depend on the new columns only after validation passes.

### Rollback

Using the same `ON CLUSTER` scope as rollout, drop the new expansion views from children to parents, restore the backed-up view definitions, and restore the previous application and configuration. If downstream consumers already switched to the new columns, roll back their queries or dataset definitions as well.
