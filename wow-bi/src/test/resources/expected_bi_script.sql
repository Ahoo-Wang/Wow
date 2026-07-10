-- global --
CREATE DATABASE IF NOT EXISTS "bi_db" ON CLUSTER '{cluster}';
CREATE DATABASE IF NOT EXISTS "bi_db_consumer" ON CLUSTER '{cluster}';
-- global --
-- clear --
-- bi.aggregate.clear --
------------------command------------------
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_command" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_command_local" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db_consumer"."bi_aggregate_command_queue" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db_consumer"."bi_aggregate_command_consumer" ON CLUSTER '{cluster}' SYNC;
------------------command------------------
------------------state------------------
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_state" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_state_event" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_state_local" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db_consumer"."bi_aggregate_state_queue" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db_consumer"."bi_aggregate_state_consumer" ON CLUSTER '{cluster}' SYNC;
------------------state------------------
------------------stateLast------------------
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_state_last" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_state_last_local" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db_consumer"."bi_aggregate_state_last_consumer" ON CLUSTER '{cluster}' SYNC;
------------------stateLast------------------
------------------expansion------------------
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_state_last_root" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_state_last_root_items" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_state_last_root_like_list_item" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_state_last_root_nested_list" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_state_last_root_nested_list_list" ON CLUSTER '{cluster}' SYNC;
DROP TABLE IF EXISTS "bi_db"."bi_aggregate_state_last_root_set" ON CLUSTER '{cluster}' SYNC;
------------------expansion------------------

-- bi.aggregate.clear --
-- clear --
-- bi.aggregate.command --
CREATE TABLE IF NOT EXISTS "bi_db"."bi_aggregate_command_local" ON CLUSTER '{cluster}'
(
    "id" String,
    "context_name" String,
    "aggregate_name" String,
    "name" String,
    "header" Map(String, String),
    "aggregate_id" String,
    "tenant_id" String,
    "owner_id" String,
    "space_id" String,
    "request_id" String,
    "aggregate_version" Nullable(UInt32),
    "is_create" Bool,
    "allow_create" Bool,
    "body_type" String,
    "body" String,
    "create_time" DateTime('Asia/Shanghai')
) ENGINE = ReplicatedMergeTree(
        '/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}')
      PARTITION BY toYYYYMM("create_time")
      ORDER BY "id"
;

CREATE TABLE IF NOT EXISTS "bi_db"."bi_aggregate_command" ON CLUSTER '{cluster}'
    AS "bi_db"."bi_aggregate_command_local"
        ENGINE = Distributed('{cluster}', "bi_db", 'bi_aggregate_command_local', sipHash64("aggregate_id"));

CREATE TABLE IF NOT EXISTS "bi_db_consumer"."bi_aggregate_command_queue" ON CLUSTER '{cluster}'
(
    "data" String
) ENGINE = Kafka('localhost:9093', 'wow.bi.aggregate.command', 'clickhouse_bi_aggregate_command_consumer', 'JSONAsString');

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_db_consumer"."bi_aggregate_command_consumer"
            ON CLUSTER '{cluster}'
            TO "bi_db"."bi_aggregate_command"
AS
SELECT JSONExtractString("data", 'id') AS "id",
       JSONExtractString("data", 'contextName') AS "context_name",
       JSONExtractString("data", 'aggregateName') AS "aggregate_name",
       JSONExtractString("data", 'name') AS "name",
       JSONExtract("data", 'header', 'Map(String, String)') AS "header",
       JSONExtractString("data", 'aggregateId') AS "aggregate_id",
       JSONExtractString("data", 'tenantId') AS "tenant_id",
       JSONExtractString("data", 'ownerId') AS "owner_id",
       JSONExtractString("data", 'spaceId') AS "space_id",
       JSONExtractString("data", 'requestId') AS "request_id",
       JSONExtract("data", 'aggregateVersion', 'Nullable(UInt32)') AS "aggregate_version",
       JSONExtractBool("data", 'isCreate') AS "is_create",
       JSONExtractBool("data", 'allowCreate') AS "allow_create",
       JSONExtractString("data", 'bodyType') AS "body_type",
       JSONExtractString("data", 'body') AS "body",
       toDateTime64(JSONExtractUInt("data", 'createTime') / 1000.0, 3, 'Asia/Shanghai') AS "create_time"
FROM "bi_db_consumer"."bi_aggregate_command_queue"
;
-- bi.aggregate.command --
-- bi.aggregate.stateEvent --
CREATE TABLE IF NOT EXISTS "bi_db"."bi_aggregate_state_local" ON CLUSTER '{cluster}'
(
    "id" String,
    "context_name" String,
    "aggregate_name" String,
    "header" Map(String, String),
    "aggregate_id" String,
    "tenant_id" String,
    "owner_id" String,
    "space_id" String,
    "command_id" String,
    "request_id" String,
    "version" UInt32,
    "state" String,
    "body" Array(String),
    "first_operator" String,
    "first_event_time" DateTime('Asia/Shanghai'),
    "create_time" DateTime('Asia/Shanghai'),
    "tags" Map(String, Array(String)),
    "deleted" Bool
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}',
                                        '{replica}', "version")
      PARTITION BY toYYYYMM("create_time")
      ORDER BY ("aggregate_id", "version")
;

CREATE TABLE IF NOT EXISTS "bi_db"."bi_aggregate_state" ON CLUSTER '{cluster}'
    AS "bi_db"."bi_aggregate_state_local"
        ENGINE = Distributed('{cluster}', "bi_db", 'bi_aggregate_state_local', sipHash64("aggregate_id"));

CREATE TABLE IF NOT EXISTS "bi_db_consumer"."bi_aggregate_state_queue" ON CLUSTER '{cluster}'
(
    "data" String
) ENGINE = Kafka('localhost:9093', 'wow.bi.aggregate.state', 'clickhouse_bi_aggregate_state_consumer', 'JSONAsString');

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_db_consumer"."bi_aggregate_state_consumer"
            ON CLUSTER '{cluster}'
            TO "bi_db"."bi_aggregate_state"
AS
SELECT JSONExtractString("data", 'id') AS "id",
       JSONExtractString("data", 'contextName') AS "context_name",
       JSONExtractString("data", 'aggregateName') AS "aggregate_name",
       JSONExtract("data", 'header', 'Map(String, String)') AS "header",
       JSONExtractString("data", 'aggregateId') AS "aggregate_id",
       JSONExtractString("data", 'tenantId') AS "tenant_id",
       JSONExtractString("data", 'ownerId') AS "owner_id",
       JSONExtractString("data", 'spaceId') AS "space_id",
       JSONExtractString("data", 'commandId') AS "command_id",
       JSONExtractString("data", 'requestId') AS "request_id",
       JSONExtractUInt("data", 'version') AS "version",
       JSONExtractString("data", 'state') AS "state",
       JSONExtractArrayRaw("data", 'body') AS "body",
       JSONExtractString("data", 'firstOperator') AS "first_operator",
       toDateTime64(JSONExtractUInt("data", 'firstEventTime') / 1000.0, 3, 'Asia/Shanghai') AS "first_event_time",
       toDateTime64(JSONExtractUInt("data", 'createTime') / 1000.0, 3, 'Asia/Shanghai') AS "create_time",
       JSONExtract("data", 'tags', 'Map(String, Array(String))') AS "tags",
       JSONExtractBool("data", 'deleted') AS "deleted"
FROM "bi_db_consumer"."bi_aggregate_state_queue"
;

CREATE VIEW IF NOT EXISTS "bi_db"."bi_aggregate_state_event" ON CLUSTER '{cluster}'
AS
WITH arrayJoin(arrayZip(arrayEnumerate("body"), "body")) AS "events"
SELECT "id",
       "context_name",
       "aggregate_name",
       "header",
       "aggregate_id",
       "tenant_id",
       "owner_id",
       "space_id",
       "command_id",
       "request_id",
       "version",
       "state",
       "events".1 AS "event_sequence",
       JSONExtract("events".2, 'id', 'String') AS "event_id",
       JSONExtract("events".2, 'name', 'String') AS "event_name",
       JSONExtract("events".2, 'revision', 'String') AS "event_revision",
       JSONExtract("events".2, 'bodyType', 'String') AS "event_body_type",
       JSONExtract("events".2, 'body', 'String') AS "event_body",
       "first_operator",
       "first_event_time",
       "create_time",
       "tags",
       "deleted"
FROM "bi_db"."bi_aggregate_state";
-- bi.aggregate.stateEvent --
-- bi.aggregate.stateLast --
CREATE TABLE IF NOT EXISTS "bi_db"."bi_aggregate_state_last_local" ON CLUSTER '{cluster}'
(
    "id" String,
    "context_name" String,
    "aggregate_name" String,
    "header" Map(String, String),
    "aggregate_id" String,
    "tenant_id" String,
    "owner_id" String,
    "space_id" String,
    "command_id" String,
    "request_id" String,
    "version" UInt32,
    "state" String,
    "body" Array(String),
    "first_operator" String,
    "first_event_time" DateTime('Asia/Shanghai'),
    "create_time" DateTime('Asia/Shanghai'),
    "tags" Map(String, Array(String)),
    "deleted" Bool
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}',
                                        '{replica}', "version")
      PARTITION BY toYYYYMM("first_event_time")
      ORDER BY ("aggregate_id")
;

CREATE TABLE IF NOT EXISTS "bi_db"."bi_aggregate_state_last" ON CLUSTER '{cluster}'
    AS "bi_db"."bi_aggregate_state_last_local"
        ENGINE = Distributed('{cluster}', "bi_db", 'bi_aggregate_state_last_local', sipHash64("aggregate_id"));

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_db_consumer"."bi_aggregate_state_last_consumer"
            ON CLUSTER '{cluster}'
            TO "bi_db"."bi_aggregate_state_last"
AS
SELECT *
FROM "bi_db"."bi_aggregate_state"
;
-- bi.aggregate.stateLast --
-- bi.aggregate.expansion --
CREATE VIEW IF NOT EXISTS "bi_db"."bi_aggregate_state_last_root" ON CLUSTER '{cluster}' AS
WITH
JSONExtractRaw("state", 'item') AS "item",
JSONExtractRaw("state", 'nested') AS "nested",
JSONExtractRaw("nested", 'child') AS "nested__child"
SELECT
JSONExtract("state", 'boolean', 'Bool') AS "boolean",
JSONExtract("state", 'byte', 'Int8') AS "byte",
JSONExtract("state", 'char', 'String') AS "char",
JSONExtract("state", 'date', 'UInt64') AS "date",
JSONExtract("state", 'dayOfWeek', 'String') AS "day_of_week",
JSONExtract("state", 'double', 'Float64') AS "double",
JSONExtract("state", 'duration', 'Decimal64(9)') AS "duration",
JSONExtract("state", 'float', 'Float32') AS "float",
JSONExtract("state", 'id', 'String') AS "id",
JSONExtract("state", 'instant', 'Decimal64(9)') AS "instant",
JSONExtract("state", 'int', 'Int32') AS "int",
JSONExtract("state", 'intArray', 'Array(Int32)') AS "int_array",
JSONExtract("item", 'id', 'String') AS "item__id",
JSONExtract("item", 'name', 'String') AS "item__name",
JSONExtractArrayRaw("state", 'items') AS "items",
JSONExtract("state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtract("state", 'likeLinkString', 'Array(String)') AS "like_link_string",
JSONExtractArrayRaw("state", 'likeListItem') AS "like_list_item",
JSONExtractRaw("state", 'likeMapItem') AS "like_map_item",
JSONExtract("state", 'likeMapString', 'Map(String, String)') AS "like_map_string",
JSONExtract("state", 'localDate', 'String') AS "local_date",
JSONExtract("state", 'localDateTime', 'String') AS "local_date_time",
JSONExtract("state", 'localTime', 'String') AS "local_time",
JSONExtract("state", 'long', 'Int64') AS "long",
JSONExtract("state", 'map', 'Map(String, String)') AS "map",
JSONExtractRaw("state", 'mapItem') AS "map_item",
JSONExtract("state", 'month', 'String') AS "month",
JSONExtract("state", 'monthDay', 'String') AS "month_day",
JSONExtract("nested__child", 'id', 'String') AS "nested__child__id",
JSONExtract("nested__child", 'name', 'String') AS "nested__child__name",
JSONExtract("nested", 'id', 'String') AS "nested__id",
JSONExtract("nested", 'name', 'String') AS "nested__name",
JSONExtractArrayRaw("state", 'nestedList') AS "nested_list",
JSONExtract("state", 'offsetDateTime', 'String') AS "offset_date_time",
JSONExtract("state", 'offsetTime', 'String') AS "offset_time",
JSONExtract("state", 'period', 'String') AS "period",
JSONExtractArrayRaw("state", 'set') AS "set",
JSONExtract("state", 'short', 'Int16') AS "short",
JSONExtract("state", 'sqlDate', 'UInt64') AS "sql_date",
JSONExtract("state", 'string', 'String') AS "string",
JSONExtract("state", 'stringList', 'Array(String)') AS "string_list",
JSONExtract("state", 'uuid', 'UUID') AS "uuid",
JSONExtract("state", 'year', 'UInt32') AS "year",
JSONExtract("state", 'yearMonth', 'String') AS "year_month",
JSONExtract("state", 'zonedDateTime', 'String') AS "zoned_date_time",
"id" AS "__id",
"aggregate_id" AS "__aggregate_id",
"tenant_id" AS "__tenant_id",
"owner_id" AS "__owner_id",
"space_id" AS "__space_id",
"command_id" AS "__command_id",
"request_id" AS "__request_id",
"version" AS "__version",
"first_operator" AS "__first_operator",
"first_event_time" AS "__first_event_time",
"create_time" AS "__create_time",
"tags" AS "__tags",
"deleted" AS "__deleted"
FROM "bi_db"."bi_aggregate_state_last";
CREATE VIEW IF NOT EXISTS "bi_db"."bi_aggregate_state_last_root_items" ON CLUSTER '{cluster}' AS
WITH
JSONExtractRaw("state", 'item') AS "item",
arrayJoin(JSONExtractArrayRaw("state", 'items')) AS "items",
JSONExtractRaw("state", 'nested') AS "nested",
JSONExtractRaw("nested", 'child') AS "nested__child"
SELECT
JSONExtract("state", 'boolean', 'Bool') AS "boolean",
JSONExtract("state", 'byte', 'Int8') AS "byte",
JSONExtract("state", 'char', 'String') AS "char",
JSONExtract("state", 'date', 'UInt64') AS "date",
JSONExtract("state", 'dayOfWeek', 'String') AS "day_of_week",
JSONExtract("state", 'double', 'Float64') AS "double",
JSONExtract("state", 'duration', 'Decimal64(9)') AS "duration",
JSONExtract("state", 'float', 'Float32') AS "float",
JSONExtract("state", 'id', 'String') AS "id",
JSONExtract("state", 'instant', 'Decimal64(9)') AS "instant",
JSONExtract("state", 'int', 'Int32') AS "int",
JSONExtract("state", 'intArray', 'Array(Int32)') AS "int_array",
JSONExtract("item", 'id', 'String') AS "item__id",
JSONExtract("item", 'name', 'String') AS "item__name",
JSONExtract("items", 'id', 'String') AS "items__id",
JSONExtract("items", 'name', 'String') AS "items__name",
JSONExtract("state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtract("state", 'likeLinkString', 'Array(String)') AS "like_link_string",
JSONExtractRaw("state", 'likeMapItem') AS "like_map_item",
JSONExtract("state", 'likeMapString', 'Map(String, String)') AS "like_map_string",
JSONExtract("state", 'localDate', 'String') AS "local_date",
JSONExtract("state", 'localDateTime', 'String') AS "local_date_time",
JSONExtract("state", 'localTime', 'String') AS "local_time",
JSONExtract("state", 'long', 'Int64') AS "long",
JSONExtract("state", 'map', 'Map(String, String)') AS "map",
JSONExtractRaw("state", 'mapItem') AS "map_item",
JSONExtract("state", 'month', 'String') AS "month",
JSONExtract("state", 'monthDay', 'String') AS "month_day",
JSONExtract("nested__child", 'id', 'String') AS "nested__child__id",
JSONExtract("nested__child", 'name', 'String') AS "nested__child__name",
JSONExtract("nested", 'id', 'String') AS "nested__id",
JSONExtract("nested", 'name', 'String') AS "nested__name",
JSONExtract("state", 'offsetDateTime', 'String') AS "offset_date_time",
JSONExtract("state", 'offsetTime', 'String') AS "offset_time",
JSONExtract("state", 'period', 'String') AS "period",
JSONExtract("state", 'short', 'Int16') AS "short",
JSONExtract("state", 'sqlDate', 'UInt64') AS "sql_date",
JSONExtract("state", 'string', 'String') AS "string",
JSONExtract("state", 'stringList', 'Array(String)') AS "string_list",
JSONExtract("state", 'uuid', 'UUID') AS "uuid",
JSONExtract("state", 'year', 'UInt32') AS "year",
JSONExtract("state", 'yearMonth', 'String') AS "year_month",
JSONExtract("state", 'zonedDateTime', 'String') AS "zoned_date_time",
"id" AS "__id",
"aggregate_id" AS "__aggregate_id",
"tenant_id" AS "__tenant_id",
"owner_id" AS "__owner_id",
"space_id" AS "__space_id",
"command_id" AS "__command_id",
"request_id" AS "__request_id",
"version" AS "__version",
"first_operator" AS "__first_operator",
"first_event_time" AS "__first_event_time",
"create_time" AS "__create_time",
"tags" AS "__tags",
"deleted" AS "__deleted"
FROM "bi_db"."bi_aggregate_state_last";
CREATE VIEW IF NOT EXISTS "bi_db"."bi_aggregate_state_last_root_like_list_item" ON CLUSTER '{cluster}' AS
WITH
JSONExtractRaw("state", 'item') AS "item",
arrayJoin(JSONExtractArrayRaw("state", 'likeListItem')) AS "like_list_item",
JSONExtractRaw("state", 'nested') AS "nested",
JSONExtractRaw("nested", 'child') AS "nested__child"
SELECT
JSONExtract("state", 'boolean', 'Bool') AS "boolean",
JSONExtract("state", 'byte', 'Int8') AS "byte",
JSONExtract("state", 'char', 'String') AS "char",
JSONExtract("state", 'date', 'UInt64') AS "date",
JSONExtract("state", 'dayOfWeek', 'String') AS "day_of_week",
JSONExtract("state", 'double', 'Float64') AS "double",
JSONExtract("state", 'duration', 'Decimal64(9)') AS "duration",
JSONExtract("state", 'float', 'Float32') AS "float",
JSONExtract("state", 'id', 'String') AS "id",
JSONExtract("state", 'instant', 'Decimal64(9)') AS "instant",
JSONExtract("state", 'int', 'Int32') AS "int",
JSONExtract("state", 'intArray', 'Array(Int32)') AS "int_array",
JSONExtract("item", 'id', 'String') AS "item__id",
JSONExtract("item", 'name', 'String') AS "item__name",
JSONExtract("state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtract("state", 'likeLinkString', 'Array(String)') AS "like_link_string",
JSONExtract("like_list_item", 'id', 'String') AS "like_list_item__id",
JSONExtract("like_list_item", 'name', 'String') AS "like_list_item__name",
JSONExtractRaw("state", 'likeMapItem') AS "like_map_item",
JSONExtract("state", 'likeMapString', 'Map(String, String)') AS "like_map_string",
JSONExtract("state", 'localDate', 'String') AS "local_date",
JSONExtract("state", 'localDateTime', 'String') AS "local_date_time",
JSONExtract("state", 'localTime', 'String') AS "local_time",
JSONExtract("state", 'long', 'Int64') AS "long",
JSONExtract("state", 'map', 'Map(String, String)') AS "map",
JSONExtractRaw("state", 'mapItem') AS "map_item",
JSONExtract("state", 'month', 'String') AS "month",
JSONExtract("state", 'monthDay', 'String') AS "month_day",
JSONExtract("nested__child", 'id', 'String') AS "nested__child__id",
JSONExtract("nested__child", 'name', 'String') AS "nested__child__name",
JSONExtract("nested", 'id', 'String') AS "nested__id",
JSONExtract("nested", 'name', 'String') AS "nested__name",
JSONExtract("state", 'offsetDateTime', 'String') AS "offset_date_time",
JSONExtract("state", 'offsetTime', 'String') AS "offset_time",
JSONExtract("state", 'period', 'String') AS "period",
JSONExtract("state", 'short', 'Int16') AS "short",
JSONExtract("state", 'sqlDate', 'UInt64') AS "sql_date",
JSONExtract("state", 'string', 'String') AS "string",
JSONExtract("state", 'stringList', 'Array(String)') AS "string_list",
JSONExtract("state", 'uuid', 'UUID') AS "uuid",
JSONExtract("state", 'year', 'UInt32') AS "year",
JSONExtract("state", 'yearMonth', 'String') AS "year_month",
JSONExtract("state", 'zonedDateTime', 'String') AS "zoned_date_time",
"id" AS "__id",
"aggregate_id" AS "__aggregate_id",
"tenant_id" AS "__tenant_id",
"owner_id" AS "__owner_id",
"space_id" AS "__space_id",
"command_id" AS "__command_id",
"request_id" AS "__request_id",
"version" AS "__version",
"first_operator" AS "__first_operator",
"first_event_time" AS "__first_event_time",
"create_time" AS "__create_time",
"tags" AS "__tags",
"deleted" AS "__deleted"
FROM "bi_db"."bi_aggregate_state_last";
CREATE VIEW IF NOT EXISTS "bi_db"."bi_aggregate_state_last_root_nested_list" ON CLUSTER '{cluster}' AS
WITH
JSONExtractRaw("state", 'item') AS "item",
JSONExtractRaw("state", 'nested') AS "nested",
JSONExtractRaw("nested", 'child') AS "nested__child",
arrayJoin(JSONExtractArrayRaw("state", 'nestedList')) AS "nested_list"
SELECT
JSONExtract("state", 'boolean', 'Bool') AS "boolean",
JSONExtract("state", 'byte', 'Int8') AS "byte",
JSONExtract("state", 'char', 'String') AS "char",
JSONExtract("state", 'date', 'UInt64') AS "date",
JSONExtract("state", 'dayOfWeek', 'String') AS "day_of_week",
JSONExtract("state", 'double', 'Float64') AS "double",
JSONExtract("state", 'duration', 'Decimal64(9)') AS "duration",
JSONExtract("state", 'float', 'Float32') AS "float",
JSONExtract("state", 'id', 'String') AS "id",
JSONExtract("state", 'instant', 'Decimal64(9)') AS "instant",
JSONExtract("state", 'int', 'Int32') AS "int",
JSONExtract("state", 'intArray', 'Array(Int32)') AS "int_array",
JSONExtract("item", 'id', 'String') AS "item__id",
JSONExtract("item", 'name', 'String') AS "item__name",
JSONExtract("state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtract("state", 'likeLinkString', 'Array(String)') AS "like_link_string",
JSONExtractRaw("state", 'likeMapItem') AS "like_map_item",
JSONExtract("state", 'likeMapString', 'Map(String, String)') AS "like_map_string",
JSONExtract("state", 'localDate', 'String') AS "local_date",
JSONExtract("state", 'localDateTime', 'String') AS "local_date_time",
JSONExtract("state", 'localTime', 'String') AS "local_time",
JSONExtract("state", 'long', 'Int64') AS "long",
JSONExtract("state", 'map', 'Map(String, String)') AS "map",
JSONExtractRaw("state", 'mapItem') AS "map_item",
JSONExtract("state", 'month', 'String') AS "month",
JSONExtract("state", 'monthDay', 'String') AS "month_day",
JSONExtract("nested__child", 'id', 'String') AS "nested__child__id",
JSONExtract("nested__child", 'name', 'String') AS "nested__child__name",
JSONExtract("nested", 'id', 'String') AS "nested__id",
JSONExtract("nested", 'name', 'String') AS "nested__name",
JSONExtract("nested_list", 'id', 'String') AS "nested_list__id",
JSONExtractArrayRaw("nested_list", 'list') AS "nested_list__list",
JSONExtract("nested_list", 'name', 'String') AS "nested_list__name",
JSONExtract("state", 'offsetDateTime', 'String') AS "offset_date_time",
JSONExtract("state", 'offsetTime', 'String') AS "offset_time",
JSONExtract("state", 'period', 'String') AS "period",
JSONExtract("state", 'short', 'Int16') AS "short",
JSONExtract("state", 'sqlDate', 'UInt64') AS "sql_date",
JSONExtract("state", 'string', 'String') AS "string",
JSONExtract("state", 'stringList', 'Array(String)') AS "string_list",
JSONExtract("state", 'uuid', 'UUID') AS "uuid",
JSONExtract("state", 'year', 'UInt32') AS "year",
JSONExtract("state", 'yearMonth', 'String') AS "year_month",
JSONExtract("state", 'zonedDateTime', 'String') AS "zoned_date_time",
"id" AS "__id",
"aggregate_id" AS "__aggregate_id",
"tenant_id" AS "__tenant_id",
"owner_id" AS "__owner_id",
"space_id" AS "__space_id",
"command_id" AS "__command_id",
"request_id" AS "__request_id",
"version" AS "__version",
"first_operator" AS "__first_operator",
"first_event_time" AS "__first_event_time",
"create_time" AS "__create_time",
"tags" AS "__tags",
"deleted" AS "__deleted"
FROM "bi_db"."bi_aggregate_state_last";
CREATE VIEW IF NOT EXISTS "bi_db"."bi_aggregate_state_last_root_nested_list_list" ON CLUSTER '{cluster}' AS
WITH
JSONExtractRaw("state", 'item') AS "item",
JSONExtractRaw("state", 'nested') AS "nested",
JSONExtractRaw("nested", 'child') AS "nested__child",
arrayJoin(JSONExtractArrayRaw("state", 'nestedList')) AS "nested_list",
arrayJoin(JSONExtractArrayRaw("nested_list", 'list')) AS "nested_list__list",
JSONExtractRaw("nested_list__list", 'child') AS "nested_list__list__child"
SELECT
JSONExtract("state", 'boolean', 'Bool') AS "boolean",
JSONExtract("state", 'byte', 'Int8') AS "byte",
JSONExtract("state", 'char', 'String') AS "char",
JSONExtract("state", 'date', 'UInt64') AS "date",
JSONExtract("state", 'dayOfWeek', 'String') AS "day_of_week",
JSONExtract("state", 'double', 'Float64') AS "double",
JSONExtract("state", 'duration', 'Decimal64(9)') AS "duration",
JSONExtract("state", 'float', 'Float32') AS "float",
JSONExtract("state", 'id', 'String') AS "id",
JSONExtract("state", 'instant', 'Decimal64(9)') AS "instant",
JSONExtract("state", 'int', 'Int32') AS "int",
JSONExtract("state", 'intArray', 'Array(Int32)') AS "int_array",
JSONExtract("item", 'id', 'String') AS "item__id",
JSONExtract("item", 'name', 'String') AS "item__name",
JSONExtract("state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtract("state", 'likeLinkString', 'Array(String)') AS "like_link_string",
JSONExtractRaw("state", 'likeMapItem') AS "like_map_item",
JSONExtract("state", 'likeMapString', 'Map(String, String)') AS "like_map_string",
JSONExtract("state", 'localDate', 'String') AS "local_date",
JSONExtract("state", 'localDateTime', 'String') AS "local_date_time",
JSONExtract("state", 'localTime', 'String') AS "local_time",
JSONExtract("state", 'long', 'Int64') AS "long",
JSONExtract("state", 'map', 'Map(String, String)') AS "map",
JSONExtractRaw("state", 'mapItem') AS "map_item",
JSONExtract("state", 'month', 'String') AS "month",
JSONExtract("state", 'monthDay', 'String') AS "month_day",
JSONExtract("nested__child", 'id', 'String') AS "nested__child__id",
JSONExtract("nested__child", 'name', 'String') AS "nested__child__name",
JSONExtract("nested", 'id', 'String') AS "nested__id",
JSONExtract("nested", 'name', 'String') AS "nested__name",
JSONExtract("nested_list", 'id', 'String') AS "nested_list__id",
JSONExtract("nested_list__list__child", 'id', 'String') AS "nested_list__list__child__id",
JSONExtract("nested_list__list__child", 'name', 'String') AS "nested_list__list__child__name",
JSONExtract("nested_list__list", 'id', 'String') AS "nested_list__list__id",
JSONExtract("nested_list__list", 'name', 'String') AS "nested_list__list__name",
JSONExtract("nested_list", 'name', 'String') AS "nested_list__name",
JSONExtract("state", 'offsetDateTime', 'String') AS "offset_date_time",
JSONExtract("state", 'offsetTime', 'String') AS "offset_time",
JSONExtract("state", 'period', 'String') AS "period",
JSONExtract("state", 'short', 'Int16') AS "short",
JSONExtract("state", 'sqlDate', 'UInt64') AS "sql_date",
JSONExtract("state", 'string', 'String') AS "string",
JSONExtract("state", 'stringList', 'Array(String)') AS "string_list",
JSONExtract("state", 'uuid', 'UUID') AS "uuid",
JSONExtract("state", 'year', 'UInt32') AS "year",
JSONExtract("state", 'yearMonth', 'String') AS "year_month",
JSONExtract("state", 'zonedDateTime', 'String') AS "zoned_date_time",
"id" AS "__id",
"aggregate_id" AS "__aggregate_id",
"tenant_id" AS "__tenant_id",
"owner_id" AS "__owner_id",
"space_id" AS "__space_id",
"command_id" AS "__command_id",
"request_id" AS "__request_id",
"version" AS "__version",
"first_operator" AS "__first_operator",
"first_event_time" AS "__first_event_time",
"create_time" AS "__create_time",
"tags" AS "__tags",
"deleted" AS "__deleted"
FROM "bi_db"."bi_aggregate_state_last";
CREATE VIEW IF NOT EXISTS "bi_db"."bi_aggregate_state_last_root_set" ON CLUSTER '{cluster}' AS
WITH
JSONExtractRaw("state", 'item') AS "item",
JSONExtractRaw("state", 'nested') AS "nested",
JSONExtractRaw("nested", 'child') AS "nested__child",
arrayJoin(JSONExtractArrayRaw("state", 'set')) AS "set"
SELECT
JSONExtract("state", 'boolean', 'Bool') AS "boolean",
JSONExtract("state", 'byte', 'Int8') AS "byte",
JSONExtract("state", 'char', 'String') AS "char",
JSONExtract("state", 'date', 'UInt64') AS "date",
JSONExtract("state", 'dayOfWeek', 'String') AS "day_of_week",
JSONExtract("state", 'double', 'Float64') AS "double",
JSONExtract("state", 'duration', 'Decimal64(9)') AS "duration",
JSONExtract("state", 'float', 'Float32') AS "float",
JSONExtract("state", 'id', 'String') AS "id",
JSONExtract("state", 'instant', 'Decimal64(9)') AS "instant",
JSONExtract("state", 'int', 'Int32') AS "int",
JSONExtract("state", 'intArray', 'Array(Int32)') AS "int_array",
JSONExtract("item", 'id', 'String') AS "item__id",
JSONExtract("item", 'name', 'String') AS "item__name",
JSONExtract("state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtract("state", 'likeLinkString', 'Array(String)') AS "like_link_string",
JSONExtractRaw("state", 'likeMapItem') AS "like_map_item",
JSONExtract("state", 'likeMapString', 'Map(String, String)') AS "like_map_string",
JSONExtract("state", 'localDate', 'String') AS "local_date",
JSONExtract("state", 'localDateTime', 'String') AS "local_date_time",
JSONExtract("state", 'localTime', 'String') AS "local_time",
JSONExtract("state", 'long', 'Int64') AS "long",
JSONExtract("state", 'map', 'Map(String, String)') AS "map",
JSONExtractRaw("state", 'mapItem') AS "map_item",
JSONExtract("state", 'month', 'String') AS "month",
JSONExtract("state", 'monthDay', 'String') AS "month_day",
JSONExtract("nested__child", 'id', 'String') AS "nested__child__id",
JSONExtract("nested__child", 'name', 'String') AS "nested__child__name",
JSONExtract("nested", 'id', 'String') AS "nested__id",
JSONExtract("nested", 'name', 'String') AS "nested__name",
JSONExtract("state", 'offsetDateTime', 'String') AS "offset_date_time",
JSONExtract("state", 'offsetTime', 'String') AS "offset_time",
JSONExtract("state", 'period', 'String') AS "period",
JSONExtract("set", 'id', 'String') AS "set__id",
JSONExtract("set", 'name', 'String') AS "set__name",
JSONExtract("state", 'short', 'Int16') AS "short",
JSONExtract("state", 'sqlDate', 'UInt64') AS "sql_date",
JSONExtract("state", 'string', 'String') AS "string",
JSONExtract("state", 'stringList', 'Array(String)') AS "string_list",
JSONExtract("state", 'uuid', 'UUID') AS "uuid",
JSONExtract("state", 'year', 'UInt32') AS "year",
JSONExtract("state", 'yearMonth', 'String') AS "year_month",
JSONExtract("state", 'zonedDateTime', 'String') AS "zoned_date_time",
"id" AS "__id",
"aggregate_id" AS "__aggregate_id",
"tenant_id" AS "__tenant_id",
"owner_id" AS "__owner_id",
"space_id" AS "__space_id",
"command_id" AS "__command_id",
"request_id" AS "__request_id",
"version" AS "__version",
"first_operator" AS "__first_operator",
"first_event_time" AS "__first_event_time",
"create_time" AS "__create_time",
"tags" AS "__tags",
"deleted" AS "__deleted"
FROM "bi_db"."bi_aggregate_state_last";
-- bi.aggregate.expansion --