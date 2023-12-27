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

## 新建数据库

```sql
create database if not exists bi_db on cluster '{cluster}';
create database if not exists bi_db_consumer on cluster '{cluster}';
```

## 同步状态事件

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

## 最新状态快照

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

## 同步聚合命令

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


