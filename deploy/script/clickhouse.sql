/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

-- select * from system.macros;

create database if not exists bi_db on cluster '{cluster}';
create database if not exists bi_db_consumer on cluster '{cluster}';

--- State Event ---
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
        ENGINE = Distributed('{cluster}', bi_db, order_order_state_local, aggregateId);

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

--- Command ---

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
        ENGINE = Distributed('{cluster}', bi_db, order_order_command_local, aggregateId);

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