-- global --
CREATE DATABASE IF NOT EXISTS "bi_db";

CREATE DATABASE IF NOT EXISTS "bi_db_consumer";
-- global --
-- ownership-registry --
CREATE TABLE "bi_db_consumer"."__wow_bi_registry_82ea723bc0a7d5fe1a1f3dfcfd696fd4"
(
    "deployment_id" FixedString(32),
    "row_kind" LowCardinality(String),
    "object_database" String,
    "object_name" String,
    "kind" LowCardinality(String),
    "aggregate" Nullable(String),
    "consumer_identity" Nullable(FixedString(32)),
    "definition_fingerprint" FixedString(32),
    "revision" UInt64,
    "status" LowCardinality(String),
    "row_fingerprint" FixedString(32),
    "recorded_at" DateTime64(3, 'UTC') DEFAULT now64(3)
) ENGINE = ReplacingMergeTree("revision")
  ORDER BY ("deployment_id", "row_kind",
            "object_database",
            "object_name")
  COMMENT 'wow-bi-registry:82ea723bc0a7d5fe1a1f3dfcfd696fd4';
-- ownership-registry --
-- ownership-registry-intent --
            INSERT INTO "bi_db_consumer"."__wow_bi_registry_82ea723bc0a7d5fe1a1f3dfcfd696fd4"
            ("deployment_id", "row_kind",
             "object_database", "object_name", "kind",
             "aggregate", "consumer_identity",
             "definition_fingerprint", "revision", "status",
             "row_fingerprint")
            VALUES
            ('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'HEAD', '', '', 'ANCHOR', NULL, NULL, '68d5a74f1d8d6da186dcff97cb73b674', 18, 'ACTIVE', '68d5a74f1d8d6da186dcff97cb73b674'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_command', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'e3ca93221fb69e69307397d550596dde', 1, 'PENDING_CREATE', '9c855f356ce0edc294296bf06aa4acd8'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_command_store', 'STORE', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '93d0420f3902feab49950f2ae72ca819', 2, 'PENDING_CREATE', '473cf28e44e27a6e3b423b5fd29be2e8'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '3b3547863c66bb4cfd019706fb95b7cf', 3, 'PENDING_CREATE', '24fec1b13ecc7fa30246c82a23f3cb69'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_event', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'fea61ceb1eb904cabb5a06771fc000c3', 4, 'PENDING_CREATE', '480de714bfedaf6103301725afbada2f'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'fcd6e180c2e46af865b8b2c7ef508a29', 5, 'PENDING_CREATE', 'e5381a24088662616b43492ff9253bc8'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '8dc2846436332dbf2b28b6c9a292feda', 6, 'PENDING_CREATE', 'ddd6c76ee05c58e81aafff3ff5338da6'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_items', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '5288161b7e2e79c86bde4625a5ee3146', 7, 'PENDING_CREATE', 'a6f1a695ecf974765a1afdb544a9a958'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_like_list_item', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'b9cc60b7579fe160946edef5de09546a', 8, 'PENDING_CREATE', '7a9c7ebc0280cdb77a0836088d539281'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_nested_list', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'ab350eb9bd5fd2d6cca4da415536f1e4', 9, 'PENDING_CREATE', 'abb8e8ebedb76f31d0ad9273f70c4874'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_nested_list_list', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'db37b35ab8457dea1f6e297c08ff4f42', 10, 'PENDING_CREATE', 'f8f538207c7cc0547737f2fcac0fec37'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_set', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'f025fbc43ac309d7476466879132d692', 11, 'PENDING_CREATE', 'adb3080f2b8ca3c5b819160913d5ba9b'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_store', 'STORE', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '82d348a3fdefe67a7555b9e4350f9c45', 12, 'PENDING_CREATE', 'cb0427f91915e81d94bb05f80fab305b'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_store', 'STORE', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '4ddaa3dee5231b5697cb890cbdda6d99', 13, 'PENDING_CREATE', '460e59014b366705312232e89c53f97c'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_command_consumer', 'CONSUMER', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'd0b7e4ae55416fc74121b0e5b51fa8ef', 14, 'PENDING_CREATE', 'd9ac026e9d42652a51c2ca3050eab9d5'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_command_queue', 'QUEUE', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '70bbe76ac011a77178f47a8c903c969e', 15, 'PENDING_CREATE', '9703bd4860f06f54e7ac48f870f587e4'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_state_consumer', 'CONSUMER', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '3727156e7faf5b4d5fec45de245d7eec', 16, 'PENDING_CREATE', 'bb2bc6b56f78e11977798cbad75d18f6'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_state_last_consumer', 'CONSUMER', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '9ec74f00d27a11e981f37d97bcb60d61', 17, 'PENDING_CREATE', '9858af91138527c02307cbd70be8d4a1'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_state_queue', 'QUEUE', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '537ddfef61d8614c6a2664da809e2662', 18, 'PENDING_CREATE', 'f6116c67c0f7cbc56922dc7cd9ad9bbd');
-- ownership-registry-intent --
-- lifecycle --
-- lifecycle --
-- bi.aggregate.commandStorage --
CREATE TABLE "bi_db"."bi_aggregate_command_store"
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
  COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"STORE","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.commandStorage --
-- bi.aggregate.stateStorage --
CREATE TABLE "bi_db"."bi_aggregate_state_store"
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
      ORDER BY ("tenant_id", "aggregate_id", "version")
      COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"STORE","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.stateStorage --
-- bi.aggregate.stateLast --
CREATE TABLE "bi_db"."bi_aggregate_state_last_store"
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
      ORDER BY ("tenant_id", "aggregate_id")
      COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"STORE","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE MATERIALIZED VIEW "bi_db_consumer"."bi_aggregate_state_last_consumer"
TO "bi_db"."bi_aggregate_state_last_store"
AS (
SELECT *
FROM "bi_db"."bi_aggregate_state_store"
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"CONSUMER","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE VIEW "bi_db"."bi_aggregate_state_last"
AS (SELECT * FROM "bi_db"."bi_aggregate_state_last_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.stateLast --
-- bi.aggregate.expansion --
CREATE VIEW "bi_db"."bi_aggregate_state_last_root" AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE VIEW "bi_db"."bi_aggregate_state_last_root_items" AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE VIEW "bi_db"."bi_aggregate_state_last_root_like_list_item" AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE VIEW "bi_db"."bi_aggregate_state_last_root_nested_list" AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE VIEW "bi_db"."bi_aggregate_state_last_root_nested_list_list" AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE VIEW "bi_db"."bi_aggregate_state_last_root_set" AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.expansion --
-- bi.aggregate.commandPublic --
CREATE VIEW "bi_db"."bi_aggregate_command"
AS (SELECT * FROM "bi_db"."bi_aggregate_command_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.commandPublic --
-- bi.aggregate.statePublic --
CREATE VIEW "bi_db"."bi_aggregate_state"
AS (SELECT * FROM "bi_db"."bi_aggregate_state_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE VIEW "bi_db"."bi_aggregate_state_event"
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
       JSONExtractRaw("events".2, 'body') AS "event_body",
       "first_operator",
       "first_event_time",
       "create_time",
       "tags",
       "deleted"
FROM "bi_db"."bi_aggregate_state"
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.statePublic --
-- bi.aggregate.commandIngress --
CREATE TABLE "bi_db_consumer"."bi_aggregate_command_queue"
("data" String)
ENGINE = Kafka('localhost:9093', 'wow.bi.aggregate.command',
               'wow-bi.39b2524473ea17aea282d31d216a901c.bi_aggregate_command_consumer', 'JSONAsString')
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"QUEUE","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE MATERIALIZED VIEW "bi_db_consumer"."bi_aggregate_command_consumer"
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"CONSUMER","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.commandIngress --
-- bi.aggregate.stateIngress --
CREATE TABLE "bi_db_consumer"."bi_aggregate_state_queue"
(
    "data" String
) ENGINE = Kafka('localhost:9093', 'wow.bi.aggregate.state',
                 'wow-bi.39b2524473ea17aea282d31d216a901c.bi_aggregate_state_consumer', 'JSONAsString')
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"QUEUE","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';

CREATE MATERIALIZED VIEW "bi_db_consumer"."bi_aggregate_state_consumer"
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","aggregate":"bi.aggregate","kind":"CONSUMER","consumerIdentity":"39b2524473ea17aea282d31d216a901c"}';
-- bi.aggregate.stateIngress --
-- ownership-registry-confirmation --
            INSERT INTO "bi_db_consumer"."__wow_bi_registry_82ea723bc0a7d5fe1a1f3dfcfd696fd4"
            ("deployment_id", "row_kind",
             "object_database", "object_name", "kind",
             "aggregate", "consumer_identity",
             "definition_fingerprint", "revision", "status",
             "row_fingerprint")
            VALUES
            ('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'HEAD', '', '', 'ANCHOR', NULL, NULL, 'fc7f24133a7bcb19533f3811b07085e6', 36, 'ACTIVE', 'fc7f24133a7bcb19533f3811b07085e6'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_command', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'e3ca93221fb69e69307397d550596dde', 19, 'ACTIVE', '34bd11516920fc2485d774ce77b2e605'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_command_store', 'STORE', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '93d0420f3902feab49950f2ae72ca819', 20, 'ACTIVE', '0d8354f9864a393bd9dcda0b6480500d'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '3b3547863c66bb4cfd019706fb95b7cf', 21, 'ACTIVE', '559e8ef665729bc2106d4d43442e6d6c'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_event', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'fea61ceb1eb904cabb5a06771fc000c3', 22, 'ACTIVE', '4b0515b004bc6545eb3fc03a8ffe7d63'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'fcd6e180c2e46af865b8b2c7ef508a29', 23, 'ACTIVE', 'd60c5022dfa5f03fc20bff36b29cc266'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '8dc2846436332dbf2b28b6c9a292feda', 24, 'ACTIVE', '44f98ba99526e19a6923e7e3f1060825'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_items', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '5288161b7e2e79c86bde4625a5ee3146', 25, 'ACTIVE', '9da2933e64381a2c74ff74145911bdf2'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_like_list_item', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'b9cc60b7579fe160946edef5de09546a', 26, 'ACTIVE', 'e139f4af9e3f53eca694cd0f93f386ca'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_nested_list', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'ab350eb9bd5fd2d6cca4da415536f1e4', 27, 'ACTIVE', '059d56e9a2b5626d5d4c77f97f82731b'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_nested_list_list', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'db37b35ab8457dea1f6e297c08ff4f42', 28, 'ACTIVE', '581a7e2e5ab9ca012f7dc05c0fdff739'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_set', 'VIEW', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'f025fbc43ac309d7476466879132d692', 29, 'ACTIVE', 'cc4f52d7e03be1bdd904c2ec2e627bdb'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_store', 'STORE', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '82d348a3fdefe67a7555b9e4350f9c45', 30, 'ACTIVE', '6b34d76855884e1ac938426d0c9a37a7'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_store', 'STORE', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '4ddaa3dee5231b5697cb890cbdda6d99', 31, 'ACTIVE', '31e3f1b6902ad455dd85401bd3861d8f'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_command_consumer', 'CONSUMER', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', 'd0b7e4ae55416fc74121b0e5b51fa8ef', 32, 'ACTIVE', '69e2546c2522185b0c5e1d93c4cb1047'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_command_queue', 'QUEUE', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '70bbe76ac011a77178f47a8c903c969e', 33, 'ACTIVE', '006ee08332e67da6d56b5cc91088baae'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_state_consumer', 'CONSUMER', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '3727156e7faf5b4d5fec45de245d7eec', 34, 'ACTIVE', '515cc34042986d090883ae299d196caa'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_state_last_consumer', 'CONSUMER', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '9ec74f00d27a11e981f37d97bcb60d61', 35, 'ACTIVE', '0ed2c53c7e8b28c1ad898d98024393af'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_state_queue', 'QUEUE', 'bi.aggregate', '39b2524473ea17aea282d31d216a901c', '537ddfef61d8614c6a2664da809e2662', 36, 'ACTIVE', '41af22bcf61783b7b77791cbc27f0d68');
-- ownership-registry-confirmation --
-- deployment-anchor --
CREATE VIEW "bi_db_consumer"."__wow_bi_deployment" AS (SELECT 1 AS "alive" WHERE 0) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"39b2524473ea17aea282d31d216a901c","topologyFingerprint":"a9268847ae91435222ea21e31ac14c47","kind":"ANCHOR","consumerIdentity":"39b2524473ea17aea282d31d216a901c","registryRevision":36}';
-- deployment-anchor --
