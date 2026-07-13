-- global --
CREATE DATABASE IF NOT EXISTS "bi_db";

CREATE DATABASE IF NOT EXISTS "bi_db_consumer";
-- global --
-- lifecycle --
-- bi.aggregate.pause-ingress --
DROP VIEW IF EXISTS "bi_db_consumer"."bi_aggregate_command_consumer" SYNC;

DROP TABLE IF EXISTS "bi_db_consumer"."bi_aggregate_command_queue" SYNC;

DROP VIEW IF EXISTS "bi_db_consumer"."bi_aggregate_state_consumer" SYNC;

DROP TABLE IF EXISTS "bi_db_consumer"."bi_aggregate_state_queue" SYNC;
-- bi.aggregate.pause-ingress --
-- lifecycle --
-- bi.aggregate.command --
CREATE TABLE IF NOT EXISTS "bi_db"."bi_aggregate_command_store"
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
    "is_void" Bool,
    "allow_create" Bool,
    "body_type" String,
    "body" String,
    "create_time" DateTime64(3, 'Asia/Shanghai')
) ENGINE = ReplacingMergeTree
  PARTITION BY toYYYYMM("create_time")
  ORDER BY "id"
  COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"STORE","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

DROP VIEW IF EXISTS "bi_db_consumer"."bi_aggregate_command_consumer" SYNC;

DROP TABLE IF EXISTS "bi_db_consumer"."bi_aggregate_command_queue" SYNC;

CREATE TABLE IF NOT EXISTS "bi_db_consumer"."bi_aggregate_command_queue"
("data" String)
ENGINE = Kafka('localhost:9093', 'wow.bi.aggregate.command',
               'wow-bi.39b2524473ea17aea282d31d216a901c.bi_aggregate_command_consumer', 'JSONAsString')
COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"QUEUE","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_db_consumer"."bi_aggregate_command_consumer"
TO "bi_db"."bi_aggregate_command_store"
AS (
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
       JSONExtractBool("data", 'isVoid') AS "is_void",
       JSONExtractBool("data", 'allowCreate') AS "allow_create",
       JSONExtractString("data", 'bodyType') AS "body_type",
       simpleJSONExtractRaw(replaceOne("data", concat('"header":', simpleJSONExtractRaw("data", 'header')), '"header":{}'), 'body') AS "body",
       toDateTime64(JSONExtractInt("data", 'createTime') / 1000.0, 3, 'Asia/Shanghai') AS "create_time"
FROM "bi_db_consumer"."bi_aggregate_command_queue"
) COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"CONSUMER","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE OR REPLACE VIEW "bi_db"."bi_aggregate_command"
AS (SELECT * FROM "bi_db"."bi_aggregate_command_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.command --
-- bi.aggregate.stateStorage --
CREATE TABLE IF NOT EXISTS "bi_db"."bi_aggregate_state_store"
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
    "first_event_time" DateTime64(3, 'Asia/Shanghai'),
    "create_time" DateTime64(3, 'Asia/Shanghai'),
    "tags" Map(String, Array(String)),
    "deleted" Bool
) ENGINE = ReplacingMergeTree("version")
      PARTITION BY toYYYYMM("create_time")
      ORDER BY ("aggregate_id", "version")
      COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"STORE","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.stateStorage --
-- bi.aggregate.stateLast --
CREATE TABLE IF NOT EXISTS "bi_db"."bi_aggregate_state_last_store"
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
    "first_event_time" DateTime64(3, 'Asia/Shanghai'),
    "create_time" DateTime64(3, 'Asia/Shanghai'),
    "tags" Map(String, Array(String)),
    "deleted" Bool
) ENGINE = ReplacingMergeTree("version")
      PARTITION BY toYYYYMM("first_event_time")
      ORDER BY ("aggregate_id")
      COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"STORE","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

DROP VIEW IF EXISTS "bi_db_consumer"."bi_aggregate_state_last_consumer" SYNC;

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_db_consumer"."bi_aggregate_state_last_consumer"
TO "bi_db"."bi_aggregate_state_last_store"
AS (
SELECT *
FROM "bi_db"."bi_aggregate_state_store"
) COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"CONSUMER","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE OR REPLACE VIEW "bi_db"."bi_aggregate_state_last"
AS (SELECT * FROM "bi_db"."bi_aggregate_state_last_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.stateLast --
-- bi.aggregate.expansion --
CREATE OR REPLACE VIEW "bi_db"."bi_aggregate_state_last_root" AS (
WITH
JSONExtractRaw("__source"."state", 'item') AS "item",
JSONExtractRaw("__source"."state", 'nested') AS "nested",
JSONExtractRaw("nested", 'child') AS "nested__child"
SELECT
JSONExtractRaw("__source"."state", 'bigDecimal') AS "big_decimal",
JSONExtract("__source"."state", 'boolean', 'Bool') AS "boolean",
JSONExtract("__source"."state", 'byte', 'Int8') AS "byte",
JSONExtract("__source"."state", 'char', 'String') AS "char",
JSONExtract("__source"."state", 'date', 'String') AS "date",
JSONExtract("__source"."state", 'dayOfWeek', 'String') AS "day_of_week",
JSONExtract("__source"."state", 'defaultEnum', 'String') AS "default_enum",
JSONExtract("__source"."state", 'double', 'Float64') AS "double",
JSONExtract("__source"."state", 'duration', 'String') AS "duration",
JSONExtract("__source"."state", 'float', 'Float32') AS "float",
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtract("__source"."state", 'instant', 'String') AS "instant",
JSONExtract("__source"."state", 'int', 'Int32') AS "int",
JSONExtract("__source"."state", 'intArray', 'Array(Int32)') AS "int_array",
JSONExtract("item", 'id', 'String') AS "item__id",
JSONExtract("item", 'name', 'String') AS "item__name",
JSONExtractArrayRaw("__source"."state", 'items') AS "items",
JSONExtract("__source"."state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtract("__source"."state", 'likeLinkString', 'Array(String)') AS "like_link_string",
JSONExtractArrayRaw("__source"."state", 'likeListItem') AS "like_list_item",
JSONExtractRaw("__source"."state", 'likeMapItem') AS "like_map_item",
JSONExtract("__source"."state", 'likeMapString', 'Map(String, String)') AS "like_map_string",
JSONExtract("__source"."state", 'localDate', 'String') AS "local_date",
JSONExtract("__source"."state", 'localDateTime', 'String') AS "local_date_time",
JSONExtract("__source"."state", 'localTime', 'String') AS "local_time",
JSONExtract("__source"."state", 'long', 'Int64') AS "long",
JSONExtract("__source"."state", 'map', 'Map(String, String)') AS "map",
JSONExtractRaw("__source"."state", 'mapItem') AS "map_item",
JSONExtract("__source"."state", 'month', 'String') AS "month",
JSONExtract("__source"."state", 'monthDay', 'String') AS "month_day",
JSONExtract("nested__child", 'id', 'String') AS "nested__child__id",
JSONExtract("nested__child", 'name', 'String') AS "nested__child__name",
JSONExtract("nested", 'id', 'String') AS "nested__id",
JSONExtract("nested", 'name', 'String') AS "nested__name",
JSONExtractArrayRaw("__source"."state", 'nestedList') AS "nested_list",
JSONExtractRaw("__source"."state", 'numericEnum') AS "numeric_enum",
JSONExtract("__source"."state", 'offsetDateTime', 'String') AS "offset_date_time",
JSONExtract("__source"."state", 'offsetTime', 'String') AS "offset_time",
JSONExtract("__source"."state", 'period', 'String') AS "period",
JSONExtractArrayRaw("__source"."state", 'set') AS "set",
JSONExtract("__source"."state", 'short', 'Int16') AS "short",
JSONExtract("__source"."state", 'sqlDate', 'String') AS "sql_date",
JSONExtract("__source"."state", 'string', 'String') AS "string",
JSONExtract("__source"."state", 'stringList', 'Array(String)') AS "string_list",
JSONExtract("__source"."state", 'uuid', 'UUID') AS "uuid",
JSONExtract("__source"."state", 'year', 'Int32') AS "year",
JSONExtract("__source"."state", 'yearMonth', 'String') AS "year_month",
JSONExtract("__source"."state", 'zonedDateTime', 'String') AS "zoned_date_time",
"__source"."state" AS "__state",
'' AS "__path",
"__source"."id" AS "__id",
"__source"."aggregate_id" AS "__aggregate_id",
"__source"."tenant_id" AS "__tenant_id",
"__source"."owner_id" AS "__owner_id",
"__source"."space_id" AS "__space_id",
"__source"."command_id" AS "__command_id",
"__source"."request_id" AS "__request_id",
"__source"."version" AS "__version",
"__source"."first_operator" AS "__first_operator",
"__source"."first_event_time" AS "__first_event_time",
"__source"."create_time" AS "__create_time",
"__source"."tags" AS "__tags",
"__source"."deleted" AS "__deleted"
FROM "bi_db"."bi_aggregate_state_last" AS "__source"
) COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE OR REPLACE VIEW "bi_db"."bi_aggregate_state_last_root_items" AS (
WITH
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'items')),
                   JSONExtractArrayRaw("__source"."state", 'items'))) AS "__cursor__items",
tupleElement("__cursor__items", 2) AS "items",
JSONExtractRaw("__source"."state", 'item') AS "item",
JSONExtractRaw("__source"."state", 'nested') AS "nested",
JSONExtractRaw("nested", 'child') AS "nested__child"
SELECT
JSONExtractRaw("__source"."state", 'bigDecimal') AS "big_decimal",
JSONExtract("__source"."state", 'boolean', 'Bool') AS "boolean",
JSONExtract("__source"."state", 'byte', 'Int8') AS "byte",
JSONExtract("__source"."state", 'char', 'String') AS "char",
JSONExtract("__source"."state", 'date', 'String') AS "date",
JSONExtract("__source"."state", 'dayOfWeek', 'String') AS "day_of_week",
JSONExtract("__source"."state", 'defaultEnum', 'String') AS "default_enum",
JSONExtract("__source"."state", 'double', 'Float64') AS "double",
JSONExtract("__source"."state", 'duration', 'String') AS "duration",
JSONExtract("__source"."state", 'float', 'Float32') AS "float",
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtract("__source"."state", 'instant', 'String') AS "instant",
JSONExtract("__source"."state", 'int', 'Int32') AS "int",
JSONExtract("__source"."state", 'intArray', 'Array(Int32)') AS "int_array",
JSONExtract("item", 'id', 'String') AS "item__id",
JSONExtract("item", 'name', 'String') AS "item__name",
JSONExtract("items", 'id', 'String') AS "items__id",
JSONExtract("items", 'name', 'String') AS "items__name",
JSONExtract("__source"."state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtract("__source"."state", 'likeLinkString', 'Array(String)') AS "like_link_string",
JSONExtractRaw("__source"."state", 'likeMapItem') AS "like_map_item",
JSONExtract("__source"."state", 'likeMapString', 'Map(String, String)') AS "like_map_string",
JSONExtract("__source"."state", 'localDate', 'String') AS "local_date",
JSONExtract("__source"."state", 'localDateTime', 'String') AS "local_date_time",
JSONExtract("__source"."state", 'localTime', 'String') AS "local_time",
JSONExtract("__source"."state", 'long', 'Int64') AS "long",
JSONExtract("__source"."state", 'map', 'Map(String, String)') AS "map",
JSONExtractRaw("__source"."state", 'mapItem') AS "map_item",
JSONExtract("__source"."state", 'month', 'String') AS "month",
JSONExtract("__source"."state", 'monthDay', 'String') AS "month_day",
JSONExtract("nested__child", 'id', 'String') AS "nested__child__id",
JSONExtract("nested__child", 'name', 'String') AS "nested__child__name",
JSONExtract("nested", 'id', 'String') AS "nested__id",
JSONExtract("nested", 'name', 'String') AS "nested__name",
JSONExtractRaw("__source"."state", 'numericEnum') AS "numeric_enum",
JSONExtract("__source"."state", 'offsetDateTime', 'String') AS "offset_date_time",
JSONExtract("__source"."state", 'offsetTime', 'String') AS "offset_time",
JSONExtract("__source"."state", 'period', 'String') AS "period",
JSONExtract("__source"."state", 'short', 'Int16') AS "short",
JSONExtract("__source"."state", 'sqlDate', 'String') AS "sql_date",
JSONExtract("__source"."state", 'string', 'String') AS "string",
JSONExtract("__source"."state", 'stringList', 'Array(String)') AS "string_list",
JSONExtract("__source"."state", 'uuid', 'UUID') AS "uuid",
JSONExtract("__source"."state", 'year', 'Int32') AS "year",
JSONExtract("__source"."state", 'yearMonth', 'String') AS "year_month",
JSONExtract("__source"."state", 'zonedDateTime', 'String') AS "zoned_date_time",
"__source"."state" AS "__state",
toUInt64(tupleElement("__cursor__items", 1) - 1) AS "__index",
concat('/items/', toString(tupleElement("__cursor__items", 1) - 1)) AS "__path",
"__source"."id" AS "__id",
"__source"."aggregate_id" AS "__aggregate_id",
"__source"."tenant_id" AS "__tenant_id",
"__source"."owner_id" AS "__owner_id",
"__source"."space_id" AS "__space_id",
"__source"."command_id" AS "__command_id",
"__source"."request_id" AS "__request_id",
"__source"."version" AS "__version",
"__source"."first_operator" AS "__first_operator",
"__source"."first_event_time" AS "__first_event_time",
"__source"."create_time" AS "__create_time",
"__source"."tags" AS "__tags",
"__source"."deleted" AS "__deleted"
FROM "bi_db"."bi_aggregate_state_last" AS "__source"
) COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE OR REPLACE VIEW "bi_db"."bi_aggregate_state_last_root_like_list_item" AS (
WITH
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'likeListItem')),
                   JSONExtractArrayRaw("__source"."state", 'likeListItem'))) AS "__cursor__like_list_item",
tupleElement("__cursor__like_list_item", 2) AS "like_list_item",
JSONExtractRaw("__source"."state", 'item') AS "item",
JSONExtractRaw("__source"."state", 'nested') AS "nested",
JSONExtractRaw("nested", 'child') AS "nested__child"
SELECT
JSONExtractRaw("__source"."state", 'bigDecimal') AS "big_decimal",
JSONExtract("__source"."state", 'boolean', 'Bool') AS "boolean",
JSONExtract("__source"."state", 'byte', 'Int8') AS "byte",
JSONExtract("__source"."state", 'char', 'String') AS "char",
JSONExtract("__source"."state", 'date', 'String') AS "date",
JSONExtract("__source"."state", 'dayOfWeek', 'String') AS "day_of_week",
JSONExtract("__source"."state", 'defaultEnum', 'String') AS "default_enum",
JSONExtract("__source"."state", 'double', 'Float64') AS "double",
JSONExtract("__source"."state", 'duration', 'String') AS "duration",
JSONExtract("__source"."state", 'float', 'Float32') AS "float",
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtract("__source"."state", 'instant', 'String') AS "instant",
JSONExtract("__source"."state", 'int', 'Int32') AS "int",
JSONExtract("__source"."state", 'intArray', 'Array(Int32)') AS "int_array",
JSONExtract("item", 'id', 'String') AS "item__id",
JSONExtract("item", 'name', 'String') AS "item__name",
JSONExtract("__source"."state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtract("__source"."state", 'likeLinkString', 'Array(String)') AS "like_link_string",
JSONExtract("like_list_item", 'id', 'String') AS "like_list_item__id",
JSONExtract("like_list_item", 'name', 'String') AS "like_list_item__name",
JSONExtractRaw("__source"."state", 'likeMapItem') AS "like_map_item",
JSONExtract("__source"."state", 'likeMapString', 'Map(String, String)') AS "like_map_string",
JSONExtract("__source"."state", 'localDate', 'String') AS "local_date",
JSONExtract("__source"."state", 'localDateTime', 'String') AS "local_date_time",
JSONExtract("__source"."state", 'localTime', 'String') AS "local_time",
JSONExtract("__source"."state", 'long', 'Int64') AS "long",
JSONExtract("__source"."state", 'map', 'Map(String, String)') AS "map",
JSONExtractRaw("__source"."state", 'mapItem') AS "map_item",
JSONExtract("__source"."state", 'month', 'String') AS "month",
JSONExtract("__source"."state", 'monthDay', 'String') AS "month_day",
JSONExtract("nested__child", 'id', 'String') AS "nested__child__id",
JSONExtract("nested__child", 'name', 'String') AS "nested__child__name",
JSONExtract("nested", 'id', 'String') AS "nested__id",
JSONExtract("nested", 'name', 'String') AS "nested__name",
JSONExtractRaw("__source"."state", 'numericEnum') AS "numeric_enum",
JSONExtract("__source"."state", 'offsetDateTime', 'String') AS "offset_date_time",
JSONExtract("__source"."state", 'offsetTime', 'String') AS "offset_time",
JSONExtract("__source"."state", 'period', 'String') AS "period",
JSONExtract("__source"."state", 'short', 'Int16') AS "short",
JSONExtract("__source"."state", 'sqlDate', 'String') AS "sql_date",
JSONExtract("__source"."state", 'string', 'String') AS "string",
JSONExtract("__source"."state", 'stringList', 'Array(String)') AS "string_list",
JSONExtract("__source"."state", 'uuid', 'UUID') AS "uuid",
JSONExtract("__source"."state", 'year', 'Int32') AS "year",
JSONExtract("__source"."state", 'yearMonth', 'String') AS "year_month",
JSONExtract("__source"."state", 'zonedDateTime', 'String') AS "zoned_date_time",
"__source"."state" AS "__state",
toUInt64(tupleElement("__cursor__like_list_item", 1) - 1) AS "__index",
concat('/likeListItem/', toString(tupleElement("__cursor__like_list_item", 1) - 1)) AS "__path",
"__source"."id" AS "__id",
"__source"."aggregate_id" AS "__aggregate_id",
"__source"."tenant_id" AS "__tenant_id",
"__source"."owner_id" AS "__owner_id",
"__source"."space_id" AS "__space_id",
"__source"."command_id" AS "__command_id",
"__source"."request_id" AS "__request_id",
"__source"."version" AS "__version",
"__source"."first_operator" AS "__first_operator",
"__source"."first_event_time" AS "__first_event_time",
"__source"."create_time" AS "__create_time",
"__source"."tags" AS "__tags",
"__source"."deleted" AS "__deleted"
FROM "bi_db"."bi_aggregate_state_last" AS "__source"
) COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE OR REPLACE VIEW "bi_db"."bi_aggregate_state_last_root_nested_list" AS (
WITH
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'nestedList')),
                   JSONExtractArrayRaw("__source"."state", 'nestedList'))) AS "__cursor__nested_list",
tupleElement("__cursor__nested_list", 2) AS "nested_list",
JSONExtractRaw("__source"."state", 'item') AS "item",
JSONExtractRaw("__source"."state", 'nested') AS "nested",
JSONExtractRaw("nested", 'child') AS "nested__child"
SELECT
JSONExtractRaw("__source"."state", 'bigDecimal') AS "big_decimal",
JSONExtract("__source"."state", 'boolean', 'Bool') AS "boolean",
JSONExtract("__source"."state", 'byte', 'Int8') AS "byte",
JSONExtract("__source"."state", 'char', 'String') AS "char",
JSONExtract("__source"."state", 'date', 'String') AS "date",
JSONExtract("__source"."state", 'dayOfWeek', 'String') AS "day_of_week",
JSONExtract("__source"."state", 'defaultEnum', 'String') AS "default_enum",
JSONExtract("__source"."state", 'double', 'Float64') AS "double",
JSONExtract("__source"."state", 'duration', 'String') AS "duration",
JSONExtract("__source"."state", 'float', 'Float32') AS "float",
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtract("__source"."state", 'instant', 'String') AS "instant",
JSONExtract("__source"."state", 'int', 'Int32') AS "int",
JSONExtract("__source"."state", 'intArray', 'Array(Int32)') AS "int_array",
JSONExtract("item", 'id', 'String') AS "item__id",
JSONExtract("item", 'name', 'String') AS "item__name",
JSONExtract("__source"."state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtract("__source"."state", 'likeLinkString', 'Array(String)') AS "like_link_string",
JSONExtractRaw("__source"."state", 'likeMapItem') AS "like_map_item",
JSONExtract("__source"."state", 'likeMapString', 'Map(String, String)') AS "like_map_string",
JSONExtract("__source"."state", 'localDate', 'String') AS "local_date",
JSONExtract("__source"."state", 'localDateTime', 'String') AS "local_date_time",
JSONExtract("__source"."state", 'localTime', 'String') AS "local_time",
JSONExtract("__source"."state", 'long', 'Int64') AS "long",
JSONExtract("__source"."state", 'map', 'Map(String, String)') AS "map",
JSONExtractRaw("__source"."state", 'mapItem') AS "map_item",
JSONExtract("__source"."state", 'month', 'String') AS "month",
JSONExtract("__source"."state", 'monthDay', 'String') AS "month_day",
JSONExtract("nested__child", 'id', 'String') AS "nested__child__id",
JSONExtract("nested__child", 'name', 'String') AS "nested__child__name",
JSONExtract("nested", 'id', 'String') AS "nested__id",
JSONExtract("nested", 'name', 'String') AS "nested__name",
JSONExtract("nested_list", 'id', 'String') AS "nested_list__id",
JSONExtractArrayRaw("nested_list", 'list') AS "nested_list__list",
JSONExtract("nested_list", 'name', 'String') AS "nested_list__name",
JSONExtractRaw("__source"."state", 'numericEnum') AS "numeric_enum",
JSONExtract("__source"."state", 'offsetDateTime', 'String') AS "offset_date_time",
JSONExtract("__source"."state", 'offsetTime', 'String') AS "offset_time",
JSONExtract("__source"."state", 'period', 'String') AS "period",
JSONExtract("__source"."state", 'short', 'Int16') AS "short",
JSONExtract("__source"."state", 'sqlDate', 'String') AS "sql_date",
JSONExtract("__source"."state", 'string', 'String') AS "string",
JSONExtract("__source"."state", 'stringList', 'Array(String)') AS "string_list",
JSONExtract("__source"."state", 'uuid', 'UUID') AS "uuid",
JSONExtract("__source"."state", 'year', 'Int32') AS "year",
JSONExtract("__source"."state", 'yearMonth', 'String') AS "year_month",
JSONExtract("__source"."state", 'zonedDateTime', 'String') AS "zoned_date_time",
"__source"."state" AS "__state",
toUInt64(tupleElement("__cursor__nested_list", 1) - 1) AS "__index",
concat('/nestedList/', toString(tupleElement("__cursor__nested_list", 1) - 1)) AS "__path",
"__source"."id" AS "__id",
"__source"."aggregate_id" AS "__aggregate_id",
"__source"."tenant_id" AS "__tenant_id",
"__source"."owner_id" AS "__owner_id",
"__source"."space_id" AS "__space_id",
"__source"."command_id" AS "__command_id",
"__source"."request_id" AS "__request_id",
"__source"."version" AS "__version",
"__source"."first_operator" AS "__first_operator",
"__source"."first_event_time" AS "__first_event_time",
"__source"."create_time" AS "__create_time",
"__source"."tags" AS "__tags",
"__source"."deleted" AS "__deleted"
FROM "bi_db"."bi_aggregate_state_last" AS "__source"
) COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE OR REPLACE VIEW "bi_db"."bi_aggregate_state_last_root_nested_list_list" AS (
WITH
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'nestedList')),
                   JSONExtractArrayRaw("__source"."state", 'nestedList'))) AS "__cursor__nested_list",
tupleElement("__cursor__nested_list", 2) AS "nested_list",
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("nested_list", 'list')),
                   JSONExtractArrayRaw("nested_list", 'list'))) AS "__cursor__nested_list__list",
tupleElement("__cursor__nested_list__list", 2) AS "nested_list__list",
JSONExtractRaw("__source"."state", 'item') AS "item",
JSONExtractRaw("__source"."state", 'nested') AS "nested",
JSONExtractRaw("nested", 'child') AS "nested__child",
JSONExtractRaw("nested_list__list", 'child') AS "nested_list__list__child"
SELECT
JSONExtractRaw("__source"."state", 'bigDecimal') AS "big_decimal",
JSONExtract("__source"."state", 'boolean', 'Bool') AS "boolean",
JSONExtract("__source"."state", 'byte', 'Int8') AS "byte",
JSONExtract("__source"."state", 'char', 'String') AS "char",
JSONExtract("__source"."state", 'date', 'String') AS "date",
JSONExtract("__source"."state", 'dayOfWeek', 'String') AS "day_of_week",
JSONExtract("__source"."state", 'defaultEnum', 'String') AS "default_enum",
JSONExtract("__source"."state", 'double', 'Float64') AS "double",
JSONExtract("__source"."state", 'duration', 'String') AS "duration",
JSONExtract("__source"."state", 'float', 'Float32') AS "float",
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtract("__source"."state", 'instant', 'String') AS "instant",
JSONExtract("__source"."state", 'int', 'Int32') AS "int",
JSONExtract("__source"."state", 'intArray', 'Array(Int32)') AS "int_array",
JSONExtract("item", 'id', 'String') AS "item__id",
JSONExtract("item", 'name', 'String') AS "item__name",
JSONExtract("__source"."state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtract("__source"."state", 'likeLinkString', 'Array(String)') AS "like_link_string",
JSONExtractRaw("__source"."state", 'likeMapItem') AS "like_map_item",
JSONExtract("__source"."state", 'likeMapString', 'Map(String, String)') AS "like_map_string",
JSONExtract("__source"."state", 'localDate', 'String') AS "local_date",
JSONExtract("__source"."state", 'localDateTime', 'String') AS "local_date_time",
JSONExtract("__source"."state", 'localTime', 'String') AS "local_time",
JSONExtract("__source"."state", 'long', 'Int64') AS "long",
JSONExtract("__source"."state", 'map', 'Map(String, String)') AS "map",
JSONExtractRaw("__source"."state", 'mapItem') AS "map_item",
JSONExtract("__source"."state", 'month', 'String') AS "month",
JSONExtract("__source"."state", 'monthDay', 'String') AS "month_day",
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
JSONExtractRaw("__source"."state", 'numericEnum') AS "numeric_enum",
JSONExtract("__source"."state", 'offsetDateTime', 'String') AS "offset_date_time",
JSONExtract("__source"."state", 'offsetTime', 'String') AS "offset_time",
JSONExtract("__source"."state", 'period', 'String') AS "period",
JSONExtract("__source"."state", 'short', 'Int16') AS "short",
JSONExtract("__source"."state", 'sqlDate', 'String') AS "sql_date",
JSONExtract("__source"."state", 'string', 'String') AS "string",
JSONExtract("__source"."state", 'stringList', 'Array(String)') AS "string_list",
JSONExtract("__source"."state", 'uuid', 'UUID') AS "uuid",
JSONExtract("__source"."state", 'year', 'Int32') AS "year",
JSONExtract("__source"."state", 'yearMonth', 'String') AS "year_month",
JSONExtract("__source"."state", 'zonedDateTime', 'String') AS "zoned_date_time",
"__source"."state" AS "__state",
toUInt64(tupleElement("__cursor__nested_list__list", 1) - 1) AS "__index",
concat('/nestedList/', toString(tupleElement("__cursor__nested_list", 1) - 1), '/list/', toString(tupleElement("__cursor__nested_list__list", 1) - 1)) AS "__path",
"__source"."id" AS "__id",
"__source"."aggregate_id" AS "__aggregate_id",
"__source"."tenant_id" AS "__tenant_id",
"__source"."owner_id" AS "__owner_id",
"__source"."space_id" AS "__space_id",
"__source"."command_id" AS "__command_id",
"__source"."request_id" AS "__request_id",
"__source"."version" AS "__version",
"__source"."first_operator" AS "__first_operator",
"__source"."first_event_time" AS "__first_event_time",
"__source"."create_time" AS "__create_time",
"__source"."tags" AS "__tags",
"__source"."deleted" AS "__deleted"
FROM "bi_db"."bi_aggregate_state_last" AS "__source"
) COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE OR REPLACE VIEW "bi_db"."bi_aggregate_state_last_root_set" AS (
WITH
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'set')),
                   JSONExtractArrayRaw("__source"."state", 'set'))) AS "__cursor__set",
tupleElement("__cursor__set", 2) AS "set",
JSONExtractRaw("__source"."state", 'item') AS "item",
JSONExtractRaw("__source"."state", 'nested') AS "nested",
JSONExtractRaw("nested", 'child') AS "nested__child"
SELECT
JSONExtractRaw("__source"."state", 'bigDecimal') AS "big_decimal",
JSONExtract("__source"."state", 'boolean', 'Bool') AS "boolean",
JSONExtract("__source"."state", 'byte', 'Int8') AS "byte",
JSONExtract("__source"."state", 'char', 'String') AS "char",
JSONExtract("__source"."state", 'date', 'String') AS "date",
JSONExtract("__source"."state", 'dayOfWeek', 'String') AS "day_of_week",
JSONExtract("__source"."state", 'defaultEnum', 'String') AS "default_enum",
JSONExtract("__source"."state", 'double', 'Float64') AS "double",
JSONExtract("__source"."state", 'duration', 'String') AS "duration",
JSONExtract("__source"."state", 'float', 'Float32') AS "float",
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtract("__source"."state", 'instant', 'String') AS "instant",
JSONExtract("__source"."state", 'int', 'Int32') AS "int",
JSONExtract("__source"."state", 'intArray', 'Array(Int32)') AS "int_array",
JSONExtract("item", 'id', 'String') AS "item__id",
JSONExtract("item", 'name', 'String') AS "item__name",
JSONExtract("__source"."state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtract("__source"."state", 'likeLinkString', 'Array(String)') AS "like_link_string",
JSONExtractRaw("__source"."state", 'likeMapItem') AS "like_map_item",
JSONExtract("__source"."state", 'likeMapString', 'Map(String, String)') AS "like_map_string",
JSONExtract("__source"."state", 'localDate', 'String') AS "local_date",
JSONExtract("__source"."state", 'localDateTime', 'String') AS "local_date_time",
JSONExtract("__source"."state", 'localTime', 'String') AS "local_time",
JSONExtract("__source"."state", 'long', 'Int64') AS "long",
JSONExtract("__source"."state", 'map', 'Map(String, String)') AS "map",
JSONExtractRaw("__source"."state", 'mapItem') AS "map_item",
JSONExtract("__source"."state", 'month', 'String') AS "month",
JSONExtract("__source"."state", 'monthDay', 'String') AS "month_day",
JSONExtract("nested__child", 'id', 'String') AS "nested__child__id",
JSONExtract("nested__child", 'name', 'String') AS "nested__child__name",
JSONExtract("nested", 'id', 'String') AS "nested__id",
JSONExtract("nested", 'name', 'String') AS "nested__name",
JSONExtractRaw("__source"."state", 'numericEnum') AS "numeric_enum",
JSONExtract("__source"."state", 'offsetDateTime', 'String') AS "offset_date_time",
JSONExtract("__source"."state", 'offsetTime', 'String') AS "offset_time",
JSONExtract("__source"."state", 'period', 'String') AS "period",
JSONExtract("set", 'id', 'String') AS "set__id",
JSONExtract("set", 'name', 'String') AS "set__name",
JSONExtract("__source"."state", 'short', 'Int16') AS "short",
JSONExtract("__source"."state", 'sqlDate', 'String') AS "sql_date",
JSONExtract("__source"."state", 'string', 'String') AS "string",
JSONExtract("__source"."state", 'stringList', 'Array(String)') AS "string_list",
JSONExtract("__source"."state", 'uuid', 'UUID') AS "uuid",
JSONExtract("__source"."state", 'year', 'Int32') AS "year",
JSONExtract("__source"."state", 'yearMonth', 'String') AS "year_month",
JSONExtract("__source"."state", 'zonedDateTime', 'String') AS "zoned_date_time",
"__source"."state" AS "__state",
toUInt64(tupleElement("__cursor__set", 1) - 1) AS "__index",
concat('/set/', toString(tupleElement("__cursor__set", 1) - 1)) AS "__path",
"__source"."id" AS "__id",
"__source"."aggregate_id" AS "__aggregate_id",
"__source"."tenant_id" AS "__tenant_id",
"__source"."owner_id" AS "__owner_id",
"__source"."space_id" AS "__space_id",
"__source"."command_id" AS "__command_id",
"__source"."request_id" AS "__request_id",
"__source"."version" AS "__version",
"__source"."first_operator" AS "__first_operator",
"__source"."first_event_time" AS "__first_event_time",
"__source"."create_time" AS "__create_time",
"__source"."tags" AS "__tags",
"__source"."deleted" AS "__deleted"
FROM "bi_db"."bi_aggregate_state_last" AS "__source"
) COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.expansion --
-- bi.aggregate.statePublic --
CREATE OR REPLACE VIEW "bi_db"."bi_aggregate_state"
AS (SELECT * FROM "bi_db"."bi_aggregate_state_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE OR REPLACE VIEW "bi_db"."bi_aggregate_state_event"
AS (
WITH arrayJoin(arrayZip(arrayEnumerate("body"),
                        "body")) AS "events"
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
FROM "bi_db"."bi_aggregate_state"
) COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.statePublic --
-- bi.aggregate.stateIngress --
DROP VIEW IF EXISTS "bi_db_consumer"."bi_aggregate_state_consumer" SYNC;

DROP TABLE IF EXISTS "bi_db_consumer"."bi_aggregate_state_queue" SYNC;

CREATE TABLE IF NOT EXISTS "bi_db_consumer"."bi_aggregate_state_queue"
(
    "data" String
) ENGINE = Kafka('localhost:9093', 'wow.bi.aggregate.state',
                 'wow-bi.39b2524473ea17aea282d31d216a901c.bi_aggregate_state_consumer', 'JSONAsString')
COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"QUEUE","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_db_consumer"."bi_aggregate_state_consumer"
TO "bi_db"."bi_aggregate_state_store"
AS (
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
       simpleJSONExtractRaw(replaceOne("data", concat('"header":', simpleJSONExtractRaw("data", 'header')), '"header":{}'), 'state') AS "state",
       JSONExtractArrayRaw("data", 'body') AS "body",
       JSONExtractString("data", 'firstOperator') AS "first_operator",
       toDateTime64(JSONExtractInt("data", 'firstEventTime') / 1000.0, 3, 'Asia/Shanghai') AS "first_event_time",
       toDateTime64(JSONExtractInt("data", 'createTime') / 1000.0, 3, 'Asia/Shanghai') AS "create_time",
       JSONExtract("data", 'tags', 'Map(String, Array(String))') AS "tags",
       JSONExtractBool("data", 'deleted') AS "deleted"
FROM "bi_db_consumer"."bi_aggregate_state_queue"
) COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":"bi.aggregate","kind":"CONSUMER","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.stateIngress --
-- deployment-anchor --
CREATE OR REPLACE VIEW "bi_db_consumer"."__wow_bi_deployment" AS (SELECT 1 AS "alive" WHERE 0) COMMENT 'wow-bi:{"protocolVersion":1,"layoutVersion":6,"deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","aggregate":null,"kind":"ANCHOR","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- deployment-anchor --
