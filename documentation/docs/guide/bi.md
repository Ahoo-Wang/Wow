# 商业智能

与传统架构有着明显区别，_Wow_ 提供了实时聚合根状态事件（`StateEvent`）和聚合命令（`Command`）作为数据分析的数据源，同时极大降低了实时 _ETL_（`Extract`, `Transform`, `Load`）的难度。

在传统架构中，实现实时 _ETL_ 通常需要经过繁琐的流程，包括 `DB->CDC->Process->DB`，而在 _Wow_ 框架中，仅需一段简单的 _SQL_ 脚本即可完成这一过程。

另外，在传统架构中，使用 _CDC_（`MySql Binlog`）数据仅记录数据的变化，缺乏明确的业务语义。进行业务分析时，需要基于数据状态的变化推断出业务语义，这往往需要进行大量的数据处理。
相较之下，_Wow_ 框架直接提供了聚合根状态事件和聚合命令作为数据分析的数据源，极大降低了数据处理的难度。

_Wow_ 提供的实时同步机制将数据实时同步至数据仓库（_ClickHouse_），为实时数据分析提供了极大的便利。这种方法为商业智能提供了强有力的支持，构建了一个实时数据分析系统，使决策制定能够基于及时而准确的信息。

- 状态事件（`StateEvent`）: 包含聚合状态的完整变化历史，同时记录了引发这些状态变更的事件关联信息。
- 聚合命令（`Command`）: 包含用户发送的所有命令。

> 状态事件、聚合命令同步流程

![商业智能](../.vuepress/public/images/bi/bi.svg)

> 事件溯源 VS 传统架构

![事件溯源 VS 传统架构](../.vuepress/public/images/eventstore/eventsourcing.svg)

## 创建数据库

- `bi_db`: 包含本地表和分布式表存储聚合根状态事件和聚合命令的数据。
- `bi_db_consumer`: 包含聚合根状态事件和聚合命令的队列，物化视图会从该队列中消费数据并转换到`bi_db`。

```sql
create database if not exists bi_db on cluster '{cluster}';
create database if not exists bi_db_consumer on cluster '{cluster}';
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
CREATE TABLE bi_db.order_order_state_local on cluster '{cluster}'
(
    id             String,
    contextName    String,
    aggregateName  String,
    header         String,
    aggregateId    String,
    tenantId       String,
    commandId      String,
    requestId      String,
    version        UInt32,
    state          String,
    body           String,
    firstOperator  String,
    firstEventTime DateTime('Asia/Shanghai'),
    createTime     DateTime('Asia/Shanghai'),
    deleted        Bool
) ENGINE = ReplicatedReplacingMergeTree(
            '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}',
            version)
      PARTITION BY toYYYYMM(createTime)
      ORDER BY (aggregateId, version)
;

create table bi_db.order_order_state on cluster '{cluster}'
    as bi_db.order_order_state_local
        ENGINE = Distributed('{cluster}', bi_db, order_order_state_local, sipHash64(aggregateId));

CREATE TABLE bi_db_consumer.order_order_state_queue on cluster '{cluster}'
(
    data String
) ENGINE = Kafka('kafka-bootstrap-servers:9092', 'wow.order-service.order.state',
                 'clickhouse_order_order_state_consumer', 'JSONAsString');

CREATE MATERIALIZED VIEW bi_db_consumer.order_order_state_consumer
            on cluster '{cluster}'
            TO bi_db.order_order_state
AS
SELECT JSONExtractString(data, 'id')            AS id,
       JSONExtractString(data, 'contextName')   AS contextName,
       JSONExtractString(data, 'aggregateName') AS aggregateName,
       JSONExtractString(data, 'header')        AS header,
       JSONExtractString(data, 'aggregateId')   AS aggregateId,
       JSONExtractString(data, 'tenantId')      AS tenantId,
       JSONExtractString(data, 'commandId')     AS commandId,
       JSONExtractString(data, 'requestId')     AS requestId,
       JSONExtractUInt(data, 'version')         AS version,
       JSONExtractString(data, 'state')         AS state,
       JSONExtractString(data, 'body')          AS body,
       JSONExtractString(data, 'firstOperator') AS firstOperator,
       toDateTime64(JSONExtractUInt(data, 'firstEventTime') / 1000.0
           , 3, 'Asia/Shanghai')                AS firstEventTime,
       toDateTime64(JSONExtractUInt(data, 'createTime') / 1000.0
           , 3, 'Asia/Shanghai')                AS createTime,
       JSONExtractBool(data, 'deleted')         AS deleted
FROM bi_db_consumer.order_order_state_queue
;
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
CREATE TABLE bi_db.order_order_state_last_local on cluster '{cluster}'
(
    id             String,
    contextName    String,
    aggregateName  String,
    header         String,
    aggregateId    String,
    tenantId       String,
    commandId      String,
    requestId      String,
    version        UInt32,
    state          String,
    body           String,
    firstOperator  String,
    firstEventTime DateTime('Asia/Shanghai'),
    createTime     DateTime('Asia/Shanghai'),
    deleted        Bool
) ENGINE = ReplicatedReplacingMergeTree(
           '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}',
           version)
--    分区算法为 `sipHash64(aggregateId) % 8`,防止相同聚合ID的状态被放置在不同分区从而导致 `Replacing` 合并失效。
      PARTITION BY sipHash64(aggregateId) % 8
      ORDER BY (aggregateId)
;

create table bi_db.order_order_state_last on cluster '{cluster}'
    as bi_db.order_order_state_last_local
        ENGINE = Distributed('{cluster}', bi_db, order_order_state_last_local, sipHash64(aggregateId));

CREATE MATERIALIZED VIEW bi_db.order_order_state_last_consumer
            on cluster '{cluster}'
            TO bi_db.order_order_state_last
AS
SELECT *
FROM bi_db.order_order_state
;
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
CREATE TABLE bi_db.order_order_command_local on cluster '{cluster}'
(
    id            String,
    contextName   String,
    aggregateName String,
    name          String,
    header        String,
    aggregateId   String,
    tenantId      String,
    requestId     String,
    aggregateVersion Nullable(UInt32),
    isCreate      Bool,
    allowCreate   Bool,
    bodyType      String,
    body          String,
    createTime    DateTime('Asia/Shanghai')
) ENGINE = ReplicatedMergeTree(
           '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}')
      PARTITION BY toYYYYMM(createTime)
      ORDER BY id
;

create table bi_db.order_order_command on cluster '{cluster}'
    as bi_db.order_order_command_local
        ENGINE = Distributed('{cluster}', bi_db, order_order_command_local, sipHash64(aggregateId));

CREATE TABLE bi_db_consumer.order_order_command_queue on cluster '{cluster}'
(
    data String
) ENGINE = Kafka('kafka-bootstrap-servers:9092', 'wow.order-service.order.command',
           'clickhouse_order_order_command_consumer', 'JSONAsString');

CREATE MATERIALIZED VIEW bi_db_consumer.order_order_command_consumer
            on cluster '{cluster}'
            TO bi_db.order_order_command
AS
SELECT JSONExtractString(data, 'id')            AS id,
       JSONExtractString(data, 'contextName')   AS contextName,
       JSONExtractString(data, 'aggregateName') AS aggregateName,
       JSONExtractString(data, 'name')          AS name,
       JSONExtractString(data, 'header')        AS header,
       JSONExtractString(data, 'aggregateId')   AS aggregateId,
       JSONExtractString(data, 'tenantId')      AS tenantId,
       JSONExtractString(data, 'requestId')     AS requestId,
       JSONExtract(data, 'aggregateVersion',
                   'Nullable(UInt32)')          AS aggregateVersion,
       JSONExtractBool(data, 'isCreate')        AS isCreate,
       JSONExtractBool(data, 'allowCreate')     AS allowCreate,
       JSONExtractString(data, 'bodyType')      AS bodyType,
       JSONExtractString(data, 'body')          AS body,
       toDateTime64(
                   JSONExtractUInt(data, 'createTime') / 1000.0
           , 3, 'Asia/Shanghai')                AS createTime
FROM bi_db_consumer.order_order_command_queue
;
```


