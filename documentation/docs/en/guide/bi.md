# Business Intelligence

## Traditional Architecture VS Event Sourcing

<center>

![Event Sourcing VS Traditional Architecture](/images/eventstore/eventsourcing.svg)
</center>

Distinct from traditional architecture, _Wow_ provides real-time aggregate root state events (`StateEvent`) and aggregate commands (`Command`) as data sources for data analysis, while greatly reducing the difficulty of real-time _ETL_ (`Extract`, `Transform`, `Load`).

In traditional architecture, implementing real-time _ETL_ usually requires a tedious process including `DB->CDC->Process->DB`, whereas in the _Wow_ framework, an automatic real-time _ETL_ script generation tool is provided. Developers only need to generate scripts through this tool and execute them in _ClickHouse_.

Additionally, in traditional architecture, using _CDC_ (`MySql Binlog`) data only records data changes and lacks clear business semantics. When conducting business analysis, it is necessary to infer business semantics based on data state changes, which often requires extensive data processing.
In contrast, the _Wow_ framework directly provides aggregate root state events and aggregate commands as data sources for data analysis, greatly reducing the difficulty of data processing.

The real-time synchronization mechanism provided by _Wow_ synchronizes data to the data warehouse (_ClickHouse_) in real-time, providing great convenience for real-time data analysis. This approach provides strong support for business intelligence, building a real-time data analysis system that enables decision-making based on timely and accurate information.

- Aggregate commands (`Command`): Contains all commands sent by users.
- State events (`StateEvent`): Contains the complete change history of aggregate states, while recording the event association information that triggered these state changes.
- Latest state events (`LastStateEvent`): Contains the latest aggregate state, similar to tables in traditional databases, specifically used to store the latest state information for each aggregate root.
- Snapshot expansion view: Compared to relational tables in traditional databases, aggregate root snapshots are organized around the aggregate root. One-to-one and one-to-many relationships are also maintained within the aggregate root, so there are no relational table situations. However, during data analysis, we need to expand the aggregate root snapshots to perform deeper data analysis based on relational patterns. The ETL script tool provided by the Wow framework can expand aggregate root snapshots layer by layer to form views with clear relationships, and this expanded view can even serve as a *wide table in the data warehouse*, providing clearer and more comprehensive data support for business decisions.

> ETL Synchronization Process

![Business Intelligence](/images/bi/bi.svg)

## Generating ETL Scripts

::: code-group
```shell [OpenAPI]
curl -X 'GET' \
  'http://localhost:8080/wow/bi/script' \
  -H 'accept: application/sql'
```
```sql [Auto-generated ETL Scripts]
-- global --
CREATE DATABASE IF NOT EXISTS bi_db ON CLUSTER '{cluster}';
CREATE DATABASE IF NOT EXISTS bi_db_consumer ON CLUSTER '{cluster}';
-- global --
-- clear --
-- example.order.clear --
------------------command------------------
DROP TABLE IF EXISTS bi_db.example_order_command ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS bi_db.example_order_command_local ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS bi_db_consumer.example_order_command_queue ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS bi_db_consumer.example_order_command_consumer ON CLUSTER '{cluster}' SYNC;
------------------command------------------
------------------state------------------
DROP TABLE IF EXISTS bi_db.example_order_state ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS bi_db.example_order_state_local ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS bi_db_consumer.example_order_state_queue ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS bi_db_consumer.example_order_state_consumer ON CLUSTER '{cluster}' SYNC;
------------------state------------------
------------------stateLast------------------
DROP TABLE IF EXISTS bi_db.example_order_state_last ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS bi_db.example_order_state_last_local ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS bi_db_consumer.example_order_state_last_consumer ON CLUSTER '{cluster}' SYNC;
------------------stateLast------------------
------------------expansion------------------
DROP TABLE IF EXISTS bi_db.example_order_state_last_root ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS bi_db.example_order_state_last_root_items ON CLUSTER '{cluster}' SYNC;
------------------expansion------------------
-- example.order.clear --
-- clear --
-- example.order.command --
CREATE TABLE IF NOT EXISTS bi_db.example_order_command_local ON CLUSTER '{cluster}'
(
    id             String,
    context_name   String,
    aggregate_name String,
    name           String,
    header Map(String, String),
    aggregate_id   String,
    tenant_id      String,
    request_id     String,
    aggregate_version Nullable(UInt32),
    is_create      Bool,
    allow_create   Bool,
    body_type      String,
    body           String,
    create_time    DateTime('Asia/Shanghai')
) ENGINE = ReplicatedMergeTree(
        '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}')
      PARTITION BY toYYYYMM(create_time)
      ORDER BY id
;

CREATE TABLE IF NOT EXISTS bi_db.example_order_command ON CLUSTER '{cluster}'
    AS bi_db.example_order_command_local
        ENGINE = Distributed('{cluster}', bi_db, example_order_command_local, sipHash64(aggregate_id));

CREATE TABLE IF NOT EXISTS bi_db_consumer.example_order_command_queue ON CLUSTER '{cluster}'
(
    data String
) ENGINE = Kafka('localhost:60886', 'wow.example.order.command', 'clickhouse_example_order_command_consumer',
                 'JSONAsString');

CREATE MATERIALIZED VIEW IF NOT EXISTS bi_db_consumer.example_order_command_consumer
            ON CLUSTER '{cluster}'
            TO bi_db.example_order_command
AS
SELECT JSONExtractString(data, 'id')                      AS id,
       JSONExtractString(data, 'contextName')             AS context_name,
       JSONExtractString(data, 'aggregateName')           AS aggregate_name,
       JSONExtractString(data, 'name')                    AS name,
       JSONExtract(data, 'header', 'Map(String, String)') AS header,
       JSONExtractString(data, 'aggregateId')             AS aggregate_id,
       JSONExtractString(data, 'tenantId')                AS tenant_id,
       JSONExtractString(data, 'requestId')               AS request_id,
       JSONExtract(data, 'aggregateVersion',
                   'Nullable(UInt32)')                    AS aggregate_version,
       JSONExtractBool(data, 'isCreate')                  AS is_create,
       JSONExtractBool(data, 'allowCreate')               AS allow_create,
       JSONExtractString(data, 'bodyType')                AS body_type,
       JSONExtractString(data, 'body')                    AS body,
       toDateTime64(
               JSONExtractUInt(data, 'createTime') / 1000.0
           , 3, 'Asia/Shanghai')                          AS create_time
FROM bi_db_consumer.example_order_command_queue
;
-- example.order.command --
-- example.order.stateEvent --
CREATE TABLE IF NOT EXISTS bi_db.example_order_state_local ON CLUSTER '{cluster}'
(
    id               String,
    context_name     String,
    aggregate_name   String,
    header Map(String, String),
    aggregate_id     String,
    tenant_id        String,
    command_id       String,
    request_id       String,
    version          UInt32,
    state            String,
    body             String,
    first_operator   String,
    first_event_time DateTime('Asia/Shanghai'),
    create_time      DateTime('Asia/Shanghai'),
    deleted          Bool
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}',
                                        '{replica}', version)
      PARTITION BY toYYYYMM(create_time)
      ORDER BY (aggregate_id, version)
;

CREATE TABLE IF NOT EXISTS bi_db.example_order_state ON CLUSTER '{cluster}'
    AS bi_db.example_order_state_local
        ENGINE = Distributed('{cluster}', bi_db, example_order_state_local, sipHash64(aggregate_id));

CREATE TABLE IF NOT EXISTS bi_db_consumer.example_order_state_queue ON CLUSTER '{cluster}'
(
    data String
) ENGINE = Kafka('localhost:60886', 'wow.example.order.state', 'clickhouse_example_order_state_consumer',
                 'JSONAsString');

CREATE MATERIALIZED VIEW IF NOT EXISTS bi_db_consumer.example_order_state_consumer
            ON CLUSTER '{cluster}'
            TO bi_db.example_order_state
AS
SELECT JSONExtractString(data, 'id')                      AS id,
       JSONExtractString(data, 'contextName')             AS context_name,
       JSONExtractString(data, 'aggregateName')           AS aggregate_name,
       JSONExtract(data, 'header', 'Map(String, String)') AS header,
       JSONExtractString(data, 'aggregateId')             AS aggregate_id,
       JSONExtractString(data, 'tenantId')                AS tenant_id,
       JSONExtractString(data, 'commandId')               AS command_id,
       JSONExtractString(data, 'requestId')               AS request_id,
       JSONExtractUInt(data, 'version')                   AS version,
       JSONExtractString(data, 'state')                   AS state,
       JSONExtractString(data, 'body')                    AS body,
       JSONExtractString(data, 'firstOperator')           AS first_operator,
       toDateTime64(JSONExtractUInt(data, 'firstEventTime') / 1000.0
           , 3, 'Asia/Shanghai')                          AS first_event_time,
       toDateTime64(JSONExtractUInt(data, 'createTime') / 1000.0
           , 3, 'Asia/Shanghai')                          AS create_time,
       JSONExtractBool(data, 'deleted')                   AS deleted
FROM bi_db_consumer.example_order_state_queue
;
-- example.order.stateEvent --
-- example.order.stateLast --
CREATE TABLE IF NOT EXISTS bi_db.example_order_state_last_local ON CLUSTER '{cluster}'
(
    id               String,
    context_name     String,
    aggregate_name   String,
    header Map(String, String),
    aggregate_id     String,
    tenant_id        String,
    command_id       String,
    request_id       String,
    version          UInt32,
    state            String,
    body             String,
    first_operator   String,
    first_event_time DateTime('Asia/Shanghai'),
    create_time      DateTime('Asia/Shanghai'),
    deleted          Bool
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}',
                                        '{replica}', version)
      PARTITION BY sipHash64(aggregate_id) % 8
      ORDER BY (aggregate_id)
;

CREATE TABLE IF NOT EXISTS bi_db.example_order_state_last ON CLUSTER '{cluster}'
    AS bi_db.example_order_state_last_local
        ENGINE = Distributed('{cluster}', bi_db, example_order_state_last_local, sipHash64(aggregate_id));

CREATE MATERIALIZED VIEW IF NOT EXISTS bi_db_consumer.example_order_state_last_consumer
            ON CLUSTER '{cluster}'
            TO bi_db.example_order_state_last
AS
SELECT *
FROM bi_db.example_order_state
;
-- example.order.stateLast --
-- example.order.expansion --
CREATE VIEW IF NOT EXISTS bi_db.example_order_state_last_root ON CLUSTER '{cluster}' AS
WITH
    JSONExtractString(state, 'address') AS address
SELECT JSONExtract(state, 'id', 'String')                  AS id,
       JSONExtract(state, 'customerId', 'String')          AS customer_id,
       JSONExtractArrayRaw(state, 'items')                 AS items,
       JSONExtract(state, 'totalAmount', 'Decimal(38,18)') AS total_amount,
       JSONExtract(state, 'paidAmount', 'Decimal(38,18)')  AS paid_amount,
       JSONExtract(state, 'status', 'String')              AS status,
       JSONExtract(state, 'payable', 'Decimal(38,18)')     AS payable,
       JSONExtract(address, 'country', 'String')           AS address__country,
       JSONExtract(address, 'province', 'String')          AS address__province,
       JSONExtract(address, 'city', 'String')              AS address__city,
       JSONExtract(address, 'district', 'String')          AS address__district,
       JSONExtract(address, 'detail', 'String')            AS address__detail,
       id                                                  AS __id,
       aggregate_id                                        AS __aggregate_id,
       tenant_id                                           AS __tenant_id,
       command_id                                          AS __command_id,
       request_id                                          AS __request_id,
       version                                             AS __version,
       first_operator                                      AS __first_operator,
       first_event_time                                    AS __first_event_time,
       create_time                                         AS __create_time,
       deleted                                             AS __deleted
FROM bi_db.example_order_state_last;

CREATE VIEW IF NOT EXISTS bi_db.example_order_state_last_root_items ON CLUSTER '{cluster}' AS
WITH
    JSONExtractString(state, 'address') AS address, arrayJoin(JSONExtractArrayRaw(state, 'items')) AS items
SELECT JSONExtract(state, 'id', 'String')                  AS id,
       JSONExtract(state, 'customerId', 'String')          AS customer_id,
       JSONExtract(state, 'totalAmount', 'Decimal(38,18)') AS total_amount,
       JSONExtract(state, 'paidAmount', 'Decimal(38,18)')  AS paid_amount,
       JSONExtract(state, 'status', 'String')              AS status,
       JSONExtract(state, 'payable', 'Decimal(38,18)')     AS payable,
       JSONExtract(address, 'country', 'String')           AS address__country,
       JSONExtract(address, 'province', 'String')          AS address__province,
       JSONExtract(address, 'city', 'String')              AS address__city,
       JSONExtract(address, 'district', 'String')          AS address__district,
       JSONExtract(address, 'detail', 'String')            AS address__detail,
       JSONExtract(items, 'id', 'String')                  AS items__id,
       JSONExtract(items, 'productId', 'String')           AS items__product_id,
       JSONExtract(items, 'price', 'Decimal(38,18)')       AS items__price,
       JSONExtract(items, 'quantity', 'Int32')             AS items__quantity,
       JSONExtract(items, 'totalPrice', 'Decimal(38,18)')  AS items__total_price,
       id                                                  AS __id,
       aggregate_id                                        AS __aggregate_id,
       tenant_id                                           AS __tenant_id,
       command_id                                          AS __command_id,
       request_id                                          AS __request_id,
       version                                             AS __version,
       first_operator                                      AS __first_operator,
       first_event_time                                    AS __first_event_time,
       create_time                                         AS __create_time,
       deleted                                             AS __deleted
FROM bi_db.example_order_state_last;
-- example.order.expansion --
```
:::

## Creating Databases

- `bi_db`: Contains local tables and distributed tables that store data for aggregate root state events and aggregate commands.
- `bi_db_consumer`: Contains queues for aggregate root state events and aggregate commands. Materialized views will consume data from this queue and transform it to `bi_db`.

```sql
create database if not exists bi_db on cluster '{cluster}';
create database if not exists bi_db_consumer on cluster '{cluster}';
```

## Aggregate Commands

::: tip
Aggregate commands are not just the various commands sent by users; they are a comprehensive archive that records all user operations.
This design not only helps in-depth analysis of user behavior patterns but also serves as an important data source for operation auditing.
Through the recording of aggregate commands, the system can track every user operation, forming a detailed operation history.

As a data source for operation auditing, aggregate commands record various operations issued by users to the system, providing strong support for system security and compliance.
When auditing, tracing user behavior, or resolving potential issues is needed, aggregate command data can be used to restore the specific operation steps of users, providing detailed operation logs to help the system better cope with security and compliance challenges.
:::

### Glossary

- Local table (`command_local`): Local table that actually stores aggregate commands.
- Distributed table (`command`): Distributed table used to query aggregate commands.
- Naming convention: `bi_db.{service alias}_{aggregate name}_{command_local|command}`
---
- Queue (`command_queue`): Queue that establishes a connection with _Kafka_ for subscribing to data streams.
- Consumer (`command_consumer`): Materialized view that consumes data from the queue and transforms it to the local table.
- Naming convention: `bi_db_consumer.{service alias}_{aggregate name}_{command_queue|command_consumer}`

### SQL Scripts

```sql
-- example.order.command --
CREATE TABLE IF NOT EXISTS bi_db.example_order_command_local ON CLUSTER '{cluster}'
(
    id             String,
    context_name   String,
    aggregate_name String,
    name           String,
    header Map(String, String),
    aggregate_id   String,
    tenant_id      String,
    request_id     String,
    aggregate_version Nullable(UInt32),
    is_create      Bool,
    allow_create   Bool,
    body_type      String,
    body           String,
    create_time    DateTime('Asia/Shanghai')
) ENGINE = ReplicatedMergeTree(
        '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}')
      PARTITION BY toYYYYMM(create_time)
      ORDER BY id
;

CREATE TABLE IF NOT EXISTS bi_db.example_order_command ON CLUSTER '{cluster}'
    AS bi_db.example_order_command_local
        ENGINE = Distributed('{cluster}', bi_db, example_order_command_local, sipHash64(aggregate_id));

CREATE TABLE IF NOT EXISTS bi_db_consumer.example_order_command_queue ON CLUSTER '{cluster}'
(
    data String
) ENGINE = Kafka('localhost:60886', 'wow.example.order.command', 'clickhouse_example_order_command_consumer',
                 'JSONAsString');

CREATE MATERIALIZED VIEW IF NOT EXISTS bi_db_consumer.example_order_command_consumer
            ON CLUSTER '{cluster}'
            TO bi_db.example_order_command
AS
SELECT JSONExtractString(data, 'id')                      AS id,
       JSONExtractString(data, 'contextName')             AS context_name,
       JSONExtractString(data, 'aggregateName')           AS aggregate_name,
       JSONExtractString(data, 'name')                    AS name,
       JSONExtract(data, 'header', 'Map(String, String)') AS header,
       JSONExtractString(data, 'aggregateId')             AS aggregate_id,
       JSONExtractString(data, 'tenantId')                AS tenant_id,
       JSONExtractString(data, 'requestId')               AS request_id,
       JSONExtract(data, 'aggregateVersion',
                   'Nullable(UInt32)')                    AS aggregate_version,
       JSONExtractBool(data, 'isCreate')                  AS is_create,
       JSONExtractBool(data, 'allowCreate')               AS allow_create,
       JSONExtractString(data, 'bodyType')                AS body_type,
       JSONExtractString(data, 'body')                    AS body,
       toDateTime64(
               JSONExtractUInt(data, 'createTime') / 1000.0
           , 3, 'Asia/Shanghai')                          AS create_time
FROM bi_db_consumer.example_order_command_queue
;
-- example.order.command --
```

## Full State Events

::: tip
The core idea of full state events (`StateEvent`) is to record the complete evolution process of aggregate states and detail the event association information that leads to state changes.

Through full state events, the system can accurately restore the evolution trajectory of aggregate states, trace the trigger reasons and context information for each state change.

This design not only enhances the system's traceability but also provides strong support for real-time data analysis, enabling decision-making based on comprehensive and accurate historical information.

In funnel analysis, the recording of full state events plays a key role, helping the system track user operations and behaviors at different stages. By tracing the complete change history of aggregate states, the system achieves the following key objectives:
1. Funnel step analysis: Records user operations and state changes at each step, accurately analyzing user behavior at each step of the funnel to understand key indicators such as conversion rates and churn rates.
2. User behavior path tracking: Through full state events, the system restores the user behavior path in the funnel, deeply understanding how users progressively perform operations, thereby identifying user habits and preferences.
3. Funnel optimization decisions: Real-time data analysis based on full state events enables decision-makers to more accurately evaluate funnel performance. This provides reliable data support for optimizing funnel processes and improving user experience.
:::

### Glossary

- Service alias (`service alias`): The alias (short name) of the service, used to distinguish different services.
- Aggregate name (`aggregate name`): The name of the aggregate root, used to distinguish different types of aggregate roots.
- Local table (`state_local`): Local table that actually stores aggregate root state events.
- Distributed table (`state`): Distributed table used to query aggregate root state events.
- Naming convention: `bi_db.{service alias}_{aggregate name}_{state_local|state}`
---
- Queue (`state_queue`): Queue that establishes a connection with _Kafka_ for subscribing to data streams.
- Consumer (`state_consumer`): Materialized view that consumes data from the queue and transforms it to the local table.
- Naming convention: `bi_db_consumer.{service alias}_{aggregate name}_{state_queue|state_consumer}`

### SQL Scripts

```sql
--- example.order.stateEvent --
CREATE TABLE IF NOT EXISTS bi_db.example_order_state_local ON CLUSTER '{cluster}'
(
    id               String,
    context_name     String,
    aggregate_name   String,
    header Map(String, String),
    aggregate_id     String,
    tenant_id        String,
    command_id       String,
    request_id       String,
    version          UInt32,
    state            String,
    body             String,
    first_operator   String,
    first_event_time DateTime('Asia/Shanghai'),
    create_time      DateTime('Asia/Shanghai'),
    deleted          Bool
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}',
                                        '{replica}', version)
      PARTITION BY toYYYYMM(create_time)
      ORDER BY (aggregate_id, version)
;

CREATE TABLE IF NOT EXISTS bi_db.example_order_state ON CLUSTER '{cluster}'
    AS bi_db.example_order_state_local
        ENGINE = Distributed('{cluster}', bi_db, example_order_state_local, sipHash64(aggregate_id));

CREATE TABLE IF NOT EXISTS bi_db_consumer.example_order_state_queue ON CLUSTER '{cluster}'
(
    data String
) ENGINE = Kafka('localhost:60886', 'wow.example.order.state', 'clickhouse_example_order_state_consumer',
                 'JSONAsString');

CREATE MATERIALIZED VIEW IF NOT EXISTS bi_db_consumer.example_order_state_consumer
            ON CLUSTER '{cluster}'
            TO bi_db.example_order_state
AS
SELECT JSONExtractString(data, 'id')                      AS id,
       JSONExtractString(data, 'contextName')             AS context_name,
       JSONExtractString(data, 'aggregateName')           AS aggregate_name,
       JSONExtract(data, 'header', 'Map(String, String)') AS header,
       JSONExtractString(data, 'aggregateId')             AS aggregate_id,
       JSONExtractString(data, 'tenantId')                AS tenant_id,
       JSONExtractString(data, 'commandId')               AS command_id,
       JSONExtractString(data, 'requestId')               AS request_id,
       JSONExtractUInt(data, 'version')                   AS version,
       JSONExtractString(data, 'state')                   AS state,
       JSONExtractString(data, 'body')                    AS body,
       JSONExtractString(data, 'firstOperator')           AS first_operator,
       toDateTime64(JSONExtractUInt(data, 'firstEventTime') / 1000.0
           , 3, 'Asia/Shanghai')                          AS first_event_time,
       toDateTime64(JSONExtractUInt(data, 'createTime') / 1000.0
           , 3, 'Asia/Shanghai')                          AS create_time,
       JSONExtractBool(data, 'deleted')                   AS deleted
FROM bi_db_consumer.example_order_state_queue
;
-- example.order.stateEvent --
```

## Latest State Events

::: tip
Latest state events are similar to tables in traditional databases, specifically used to store the latest state information for each aggregate root.
To make it easier for users accustomed to traditional databases to get started, _Wow_ provides latest state events, allowing users to directly query the latest state information of aggregate roots.
This design aims to provide a more intuitive and familiar data query interface, making it convenient for users to quickly obtain the required aggregate root state.
:::

### Glossary

- Local table (`state_last_local`): Local table that actually stores the latest aggregate root state events.
- Distributed table (`state_last`): Distributed table used to query aggregate root state events.
- Naming convention: `bi_db.{service alias}_{aggregate name}_{state_last_local|state_last}`
---
- Consumer (`state_last_consumer`): Materialized view that consumes data from *full state events* and transforms it to the latest state events local table.
- Naming convention: `bi_db.{service alias}_{aggregate name}_state_last_consumer`

### SQL Scripts

```sql
-- example.order.stateLast --
CREATE TABLE IF NOT EXISTS bi_db.example_order_state_last_local ON CLUSTER '{cluster}'
(
    id               String,
    context_name     String,
    aggregate_name   String,
    header Map(String, String),
    aggregate_id     String,
    tenant_id        String,
    command_id       String,
    request_id       String,
    version          UInt32,
    state            String,
    body             String,
    first_operator   String,
    first_event_time DateTime('Asia/Shanghai'),
    create_time      DateTime('Asia/Shanghai'),
    deleted          Bool
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}',
                                        '{replica}', version)
      PARTITION BY sipHash64(aggregate_id) % 8
      ORDER BY (aggregate_id)
;

CREATE TABLE IF NOT EXISTS bi_db.example_order_state_last ON CLUSTER '{cluster}'
    AS bi_db.example_order_state_last_local
        ENGINE = Distributed('{cluster}', bi_db, example_order_state_last_local, sipHash64(aggregate_id));

CREATE MATERIALIZED VIEW IF NOT EXISTS bi_db_consumer.example_order_state_last_consumer
            ON CLUSTER '{cluster}'
            TO bi_db.example_order_state_last
AS
SELECT *
FROM bi_db.example_order_state
;
-- example.order.stateLast --
```

## Snapshot Expansion Views

::: tip
Compared to relational tables in traditional databases, aggregate root snapshots are organized around the aggregate root.
One-to-one and one-to-many relationships are also maintained within the aggregate root, so there are no relational table situations.
However, during data analysis, we need to expand the aggregate root snapshots to perform deeper data analysis based on relational patterns.

The ETL script tool provided by the Wow framework can expand aggregate root snapshots layer by layer to form views with clear relationships, and this expanded view can even serve as a *wide table in the data warehouse*, providing clearer and more comprehensive data support for business decisions.
:::

### Glossary

- `root` view: Expands one-to-one relationship objects into multiple fields.
- List view: Expands one-to-many relationship objects into multiple rows, each row containing all fields of a relationship object while inheriting parent object fields.

The _Wow-ETL_ script tool will expand all aggregate root snapshots layer by layer (supporting nested layers), generating multiple views for relational pattern-based data analysis.

:::code-group
```json [Data Format]
{
  "id": "0U0woS8Z0001001",
  "customerId": "customerId",
  "items": [
    {
      "id": "0U0woSBA0001002",
      "productId": "string",
      "price": 10,
      "quantity": 10,
      "totalPrice": 100
    }
  ],
  "address": {
    "country": "string",
    "province": "string",
    "city": "string",
    "district": "string",
    "detail": "string"
  },
  "totalAmount": 100,
  "paidAmount": 0,
  "status": "CREATED",
  "payable": 100
}
```

```sql [Root View]
CREATE VIEW IF NOT EXISTS bi_db.example_order_state_last_root ON CLUSTER '{cluster}' AS
WITH
    JSONExtractString(state, 'address') AS address
SELECT JSONExtract(state, 'id', 'String')                  AS id,
       JSONExtract(state, 'customerId', 'String')          AS customer_id,
       JSONExtractArrayRaw(state, 'items')                 AS items,
       JSONExtract(state, 'totalAmount', 'Decimal(38,18)') AS total_amount,
       JSONExtract(state, 'paidAmount', 'Decimal(38,18)')  AS paid_amount,
       JSONExtract(state, 'status', 'String')              AS status,
       JSONExtract(state, 'payable', 'Decimal(38,18)')     AS payable,
       JSONExtract(address, 'country', 'String')           AS address__country,
       JSONExtract(address, 'province', 'String')          AS address__province,
       JSONExtract(address, 'city', 'String')              AS address__city,
       JSONExtract(address, 'district', 'String')          AS address__district,
       JSONExtract(address, 'detail', 'String')            AS address__detail,
       id                                                  AS __id,
       aggregate_id                                        AS __aggregate_id,
       tenant_id                                           AS __tenant_id,
       command_id                                          AS __command_id,
       request_id                                          AS __request_id,
       version                                             AS __version,
       first_operator                                      AS __first_operator,
       first_event_time                                    AS __first_event_time,
       create_time                                         AS __create_time,
       deleted                                             AS __deleted
FROM bi_db.example_order_state_last;
```
```sql [List View]
CREATE VIEW IF NOT EXISTS bi_db.example_order_state_last_root_items ON CLUSTER '{cluster}' AS
WITH
    JSONExtractString(state, 'address') AS address, 
    arrayJoin(JSONExtractArrayRaw(state, 'items')) AS items
SELECT JSONExtract(state, 'id', 'String')                  AS id,
       JSONExtract(state, 'customerId', 'String')          AS customer_id,
       JSONExtract(state, 'totalAmount', 'Decimal(38,18)') AS total_amount,
       JSONExtract(state, 'paidAmount', 'Decimal(38,18)')  AS paid_amount,
       JSONExtract(state, 'status', 'String')              AS status,
       JSONExtract(state, 'payable', 'Decimal(38,18)')     AS payable,
       JSONExtract(address, 'country', 'String')           AS address__country,
       JSONExtract(address, 'province', 'String')          AS address__province,
       JSONExtract(address, 'city', 'String')              AS address__city,
       JSONExtract(address, 'district', 'String')          AS address__district,
       JSONExtract(address, 'detail', 'String')            AS address__detail,
       JSONExtract(items, 'id', 'String')                  AS items__id,
       JSONExtract(items, 'productId', 'String')           AS items__product_id,
       JSONExtract(items, 'price', 'Decimal(38,18)')       AS items__price,
       JSONExtract(items, 'quantity', 'Int32')             AS items__quantity,
       JSONExtract(items, 'totalPrice', 'Decimal(38,18)')  AS items__total_price,
       id                                                  AS __id,
       aggregate_id                                        AS __aggregate_id,
       tenant_id                                           AS __tenant_id,
       command_id                                          AS __command_id,
       request_id                                          AS __request_id,
       version                                             AS __version,
       first_operator                                      AS __first_operator,
       first_event_time                                    AS __first_event_time,
       create_time                                         AS __create_time,
       deleted                                             AS __deleted
FROM bi_db.example_order_state_last;
```
:::