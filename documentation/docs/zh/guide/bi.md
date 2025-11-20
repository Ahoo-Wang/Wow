# 商业智能

## 传统架构 VS 事件溯源

<center>

![事件溯源 VS 传统架构](../../public/images/eventstore/eventsourcing.svg)
</center>

与传统架构有着明显区别，_Wow_ 提供了实时聚合根状态事件（`StateEvent`）和聚合命令（`Command`）作为数据分析的数据源，同时极大降低了实时 _ETL_（`Extract`, `Transform`, `Load`）的难度。

在传统架构中，实现实时 _ETL_ 通常需要经过繁琐的流程，包括 `DB->CDC->Process->DB`，而在 _Wow_ 框架中，提供了自动生成实时 _ETL_ 脚本的工具，开发者仅需通过该工具生成脚本并在 _ClickHouse_ 中执行即可。

另外，在传统架构中，使用 _CDC_（`MySql Binlog`）数据仅记录数据的变化，缺乏明确的业务语义。进行业务分析时，需要基于数据状态的变化推断出业务语义，这往往需要进行大量的数据处理。
相较之下，_Wow_ 框架直接提供了聚合根状态事件和聚合命令作为数据分析的数据源，极大降低了数据处理的难度。

_Wow_ 提供的实时同步机制将数据实时同步至数据仓库（_ClickHouse_），为实时数据分析提供了极大的便利。这种方法为商业智能提供了强有力的支持，构建了一个实时数据分析系统，使决策制定能够基于及时而准确的信息。

- 聚合命令（`Command`）: 包含用户发送的所有命令。
- 状态事件（`StateEvent`）: 包含聚合状态的完整变化历史，同时记录了引发这些状态变更的事件关联信息。
- 最新状态事件（`LastStateEvent`）: 包含最新的聚合状态，类似于传统数据库中的表，专门用于存储每个聚合根的最新状态信息。
- 快照展开视图: 相比于传统数据库的关系表，聚合根快照是以聚合根为中心组织在一起的。 一对一、一对多的关系也是维护在聚合根内部的，所以并不会出现关系表的情况。 但是在数据分析时，我们需要将聚合根的快照展开，以便基于关系模式进行更有深度的数据分析。 Wow 框架提供的 ETL 脚本工具能够将聚合根快照逐层展开，形成关系清晰的视图，该展开视图甚至可以作为*数据仓库的大宽表*，为业务决策提供更清晰、更全面的数据支持。

> ETL 同步流程

![商业智能](../../public/images/bi/bi.svg)

## 生成 ETL 脚本

::: code-group
```shell [OpenAPI]
curl -X 'GET' \
  'http://localhost:8080/wow/bi/script' \
  -H 'accept: application/sql'
```
```sql [自动生成的 ETL 脚本]
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

## 创建数据库

- `bi_db`: 包含本地表和分布式表存储聚合根状态事件和聚合命令的数据。
- `bi_db_consumer`: 包含聚合根状态事件和聚合命令的队列，物化视图会从该队列中消费数据并转换到`bi_db`。

```sql
create database if not exists bi_db on cluster '{cluster}';
create database if not exists bi_db_consumer on cluster '{cluster}';
```

## 聚合命令

::: tip
聚合命令不仅仅是用户发送的各种命令，它更是一个记录用户所有操作的全面档案。
这一设计不仅有助于深入分析用户的行为模式，还能作为操作审计的重要数据源。
通过聚合命令的记录，系统能够追踪用户的每一次操作，形成详实的操作历史。

作为操作审计的数据源，聚合命令记录了用户对系统发出的各类操作，为系统的安全性和合规性提供了有力的支持。
在需要进行审计、追溯用户行为或解决潜在问题时，聚合命令的数据可以被用于还原用户的具体操作步骤，提供详实的操作日志，帮助系统更好地应对安全与合规的挑战。
:::

### 名词解释

- 本地表（`command_local`）: 本地表，实际存放聚合命令。
- 分布式表（`command`）: 分布式表，用于查询聚合命令。
- 命名约定：`bi_db.{service alias}_{aggregate name}_{command_local|command}`
---
- 队列（`command_queue`）: 队列，建立与 _Kafka_ 的连接，用于订阅数据流。
- 消费者（`command_consumer`）: 物化视图，从队列中消费数据并转换到本地表。
- 命名约定：`bi_db_consumer.{service alias}_{aggregate name}_{command_queue|command_consumer}`

### SQL 脚本

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

## 全量状态事件

::: tip
全量状态事件(`StateEvent`)的设计核心思想在于记录聚合状态的完整演变历程，并详细记录导致状态变更的事件关联信息。

通过全量状态事件，系统能够准确还原聚合状态的演变轨迹，追溯每一次状态变更的触发原因和上下文信息。

这一设计不仅增强了系统的可追溯性，而且为实时数据分析提供了强有力的支持，使决策制定能够基于全面而准确的历史信息。

在漏斗分析中，全量状态事件的记录具有关键作用，可以帮助系统跟踪用户在不同阶段的操作和行为。通过追溯聚合状态的完整变化历史，系统实现了以下关键目标：
1. 漏斗步骤分析： 记录了用户每一步操作和状态变更，精确分析用户在漏斗的每个步骤的行为，以了解转化率、流失率等关键指标。
2. 用户行为路径追踪： 通过全量状态事件，系统还原了用户在漏斗中的行为路径，深入了解用户逐步进行操作的方式，从而识别用户的习惯和偏好。 
3. 漏斗优化决策： 实时数据分析基于全量状态事件，使决策者能够更准确地评估漏斗的性能。这为优化漏斗流程、提升用户体验提供了可靠的数据支持。
:::

### 名词解释

- 服务别名（`service alias`）: 服务的别名(短名称)，用于区分不同的服务。
- 聚合名称（`aggregate name`）: 聚合根的名称，用于区分不同类型聚合根。
- 本地表（`state_local`）: 本地表，实际存放聚合根状态事件。
- 分布式表（`state`）: 分布式表，用于查询聚合根状态事件。
- 命名约定：`bi_db.{service alias}_{aggregate name}_{state_local|state}`
---
- 队列（`state_queue`）: 队列，建立与 _Kafka_ 的连接，用于订阅数据流。
- 消费者（`state_consumer`）: 物化视图，从队列中消费数据并转换到本地表。
- 命名约定：`bi_db_consumer.{service alias}_{aggregate name}_{state_queue|state_consumer}`

### SQL 脚本

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

## 最新状态事件

::: tip
最新状态事件类似于传统数据库中的表，专门用于存储每个聚合根的最新状态信息。
为了让习惯于传统数据库的使用者更容易上手，_Wow_ 提供了最新状态事件，使用户能够直接查询聚合根的最新状态信息。
这一设计旨在提供更直观、熟悉的数据查询接口，方便用户快速获取所需的聚合根状态。
:::

### 名词解释

- 本地表（`state_last_local`）: 本地表，实际存放聚合根最新状态事件。
- 分布式表（`state_last`）: 分布式表，用于查询聚合根状态事件。
- 命名约定：`bi_db.{service alias}_{aggregate name}_{state_last_local|state_last}`
---
- 消费者（`state_last_consumer`）: 物化视图，从*全量状态事件*中消费数据并转换到最新状态事件本地表。
- 命名约定：`bi_db.{service alias}_{aggregate name}_state_last_consumer`

### SQL 脚本

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

## 快照展开视图

::: tip
相比于传统数据库的关系表，聚合根快照是以聚合根为中心组织在一起的。
一对一、一对多的关系也是维护在聚合根内部的，所以并不会出现关系表的情况。
但是在数据分析时，我们需要将聚合根的快照展开，以便基于关系模式进行更有深度的数据分析。

Wow 框架提供的 ETL 脚本工具能够将聚合根快照逐层展开，形成关系清晰的视图，该展开视图甚至可以作为*数据仓库的大宽表*，为业务决策提供更清晰、更全面的数据支持。
:::

### 名词解释

- `root` 视图: 将一对一的关系对象展开成多个字段。
- 列表视图: 将一对多的关系对象展开成多行，每行包含一个关系对象的所有字段，同时继承父对象字段。

_Wow-ETL_  脚本工具会逐层展开所有的聚合根快照(支持层层嵌套)，生成多个视图，以便于基于关系模式的数据分析。

:::code-group
```json [数据格式]
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

```sql [root 视图]
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
```sql [列表视图]
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