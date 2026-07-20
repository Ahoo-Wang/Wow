-- global --
CREATE DATABASE IF NOT EXISTS "bi_db" ON CLUSTER '{cluster}';

CREATE DATABASE IF NOT EXISTS "bi_db_consumer" ON CLUSTER '{cluster}';
-- global --
-- ownership-registry --
CREATE TABLE IF NOT EXISTS "bi_db_consumer"."__wow_bi_registry_82ea723bc0a7d5fe1a1f3dfcfd696fd4" ON CLUSTER '{cluster}'
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
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/{installation}/{cluster}/control/wow-bi/82ea723bc0a7d5fe1a1f3dfcfd696fd4', '{shard}-{replica}', "revision")
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
            ('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'HEAD', '', '', 'ANCHOR', NULL, NULL, 'c318bf736603d4ec318fdd7724415d54', 21, 'ACTIVE', 'c318bf736603d4ec318fdd7724415d54'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_command', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'e3ca93221fb69e69307397d550596dde', 1, 'PENDING_CREATE', 'ade2670e7fd4d81d2215e4e61eb27680'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_command_store', 'STORE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '19f1974c0a6a4adbfa537cc62c184c49', 2, 'PENDING_CREATE', 'c70a84c2d017660d870b3fb73f1c030e'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_command_store_local', 'STORE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'e449b5eba702a027ec086eb5341193f0', 3, 'PENDING_CREATE', '973a597dbd8a7e9f2a35c3456a055004'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '3b3547863c66bb4cfd019706fb95b7cf', 4, 'PENDING_CREATE', '2a3b8fe54d5994454b6d6ae1d2b82710'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_event', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'fea61ceb1eb904cabb5a06771fc000c3', 5, 'PENDING_CREATE', '2c01df13f911425f322eb6eb7d236377'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'fcd6e180c2e46af865b8b2c7ef508a29', 6, 'PENDING_CREATE', 'c010f20cf6a1a9a269236484018c6fc5'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '8dc2846436332dbf2b28b6c9a292feda', 7, 'PENDING_CREATE', '54609785be676b8837e2b1dc4f4a7fe9'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_items', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '5288161b7e2e79c86bde4625a5ee3146', 8, 'PENDING_CREATE', '63844c4b96deec65e20d1b7cbda5e411'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_like_list_item', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'b9cc60b7579fe160946edef5de09546a', 9, 'PENDING_CREATE', '1a94a936eb30f7568b010aa283a8e183'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_nested_list', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'ab350eb9bd5fd2d6cca4da415536f1e4', 10, 'PENDING_CREATE', 'e850a21fbe0d5843909869faadd4ad8d'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_nested_list_list', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'db37b35ab8457dea1f6e297c08ff4f42', 11, 'PENDING_CREATE', 'b37d6be5398ac33a29dc2fa33462dc39'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_set', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'f025fbc43ac309d7476466879132d692', 12, 'PENDING_CREATE', 'b9eb9fe73ae763d356dd868d501e2514'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_store', 'STORE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'bd592ff1dac6203316f97f39f6c85c99', 13, 'PENDING_CREATE', 'd809f930ed3dff1bc27c14904deeb6c4'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_store_local', 'STORE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'a1dc9b4a073719d0a994079e667e39b8', 14, 'PENDING_CREATE', 'd04ca7da7c1c9b45784e5fdd43bce73b'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_store', 'STORE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'cca42da69a71bcf60b13328448de10f5', 15, 'PENDING_CREATE', '13b1715b5865597179dc1c2af89511a9'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_store_local', 'STORE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '7fec1b46d373f8edf723361151c04c2e', 16, 'PENDING_CREATE', '8cb0527e793fd1de4a8d4225cbfd97c2'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_command_consumer', 'CONSUMER', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'd0b7e4ae55416fc74121b0e5b51fa8ef', 17, 'PENDING_CREATE', 'd04cdd032540324e6e11acfa03e0bb34'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_command_queue', 'QUEUE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '70bbe76ac011a77178f47a8c903c969e', 18, 'PENDING_CREATE', '67c9b720a440c2fb945c6c9fea7ec043'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_state_consumer', 'CONSUMER', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '3727156e7faf5b4d5fec45de245d7eec', 19, 'PENDING_CREATE', '3097ff4c8ff985d2796fe1f31a496f13'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_state_last_consumer', 'CONSUMER', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '9ec74f00d27a11e981f37d97bcb60d61', 20, 'PENDING_CREATE', 'c8473010825170fab270ccf9d47edc04'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_state_queue', 'QUEUE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '537ddfef61d8614c6a2664da809e2662', 21, 'PENDING_CREATE', '6813e6fa2156ebc030a0952dc9e36e2d');
-- ownership-registry-intent --
-- lifecycle --
-- lifecycle --
-- bi.aggregate.commandStorage --
CREATE TABLE "bi_db"."bi_aggregate_command_store_local" ON CLUSTER '{cluster}'
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
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}')
  PARTITION BY toYYYYMM("create_time")
  ORDER BY "id"
  COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"STORE","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE TABLE "bi_db"."bi_aggregate_command_store" ON CLUSTER '{cluster}'
AS "bi_db"."bi_aggregate_command_store_local"
ENGINE = Distributed('{cluster}', "bi_db",
                     'bi_aggregate_command_store_local', sipHash64("aggregate_id"))
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"STORE","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';
-- bi.aggregate.commandStorage --
-- bi.aggregate.stateStorage --
CREATE TABLE "bi_db"."bi_aggregate_state_store_local" ON CLUSTER '{cluster}'
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
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}', "version")
      PARTITION BY toYYYYMM("create_time")
      ORDER BY ("tenant_id", "aggregate_id", "version")
      COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"STORE","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE TABLE "bi_db"."bi_aggregate_state_store" ON CLUSTER '{cluster}'
AS "bi_db"."bi_aggregate_state_store_local"
ENGINE = Distributed('{cluster}', "bi_db",
                     'bi_aggregate_state_store_local', sipHash64("tenant_id", "aggregate_id"))
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"STORE","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';
-- bi.aggregate.stateStorage --
-- bi.aggregate.stateLast --
CREATE TABLE "bi_db"."bi_aggregate_state_last_store_local" ON CLUSTER '{cluster}'
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
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/{installation}/{cluster}/tables/{shard}/{database}/{table}', '{replica}', "version")
      PARTITION BY toYYYYMM("first_event_time")
      ORDER BY ("tenant_id", "aggregate_id")
      COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"STORE","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE TABLE "bi_db"."bi_aggregate_state_last_store" ON CLUSTER '{cluster}'
AS "bi_db"."bi_aggregate_state_last_store_local"
ENGINE = Distributed('{cluster}', "bi_db",
                     'bi_aggregate_state_last_store_local', sipHash64("tenant_id", "aggregate_id"))
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"STORE","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE MATERIALIZED VIEW "bi_db_consumer"."bi_aggregate_state_last_consumer" ON CLUSTER '{cluster}'
TO "bi_db"."bi_aggregate_state_last_store"
AS (
SELECT *
FROM "bi_db"."bi_aggregate_state_store"
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"CONSUMER","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE VIEW "bi_db"."bi_aggregate_state_last" ON CLUSTER '{cluster}'
AS (SELECT * FROM "bi_db"."bi_aggregate_state_last_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';
-- bi.aggregate.stateLast --
-- bi.aggregate.expansion --
CREATE VIEW "bi_db"."bi_aggregate_state_last_root" ON CLUSTER '{cluster}' AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE VIEW "bi_db"."bi_aggregate_state_last_root_items" ON CLUSTER '{cluster}' AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE VIEW "bi_db"."bi_aggregate_state_last_root_like_list_item" ON CLUSTER '{cluster}' AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE VIEW "bi_db"."bi_aggregate_state_last_root_nested_list" ON CLUSTER '{cluster}' AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE VIEW "bi_db"."bi_aggregate_state_last_root_nested_list_list" ON CLUSTER '{cluster}' AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE VIEW "bi_db"."bi_aggregate_state_last_root_set" ON CLUSTER '{cluster}' AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';
-- bi.aggregate.expansion --
-- bi.aggregate.commandPublic --
CREATE VIEW "bi_db"."bi_aggregate_command" ON CLUSTER '{cluster}'
AS (SELECT * FROM "bi_db"."bi_aggregate_command_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';
-- bi.aggregate.commandPublic --
-- bi.aggregate.statePublic --
CREATE VIEW "bi_db"."bi_aggregate_state" ON CLUSTER '{cluster}'
AS (SELECT * FROM "bi_db"."bi_aggregate_state_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE VIEW "bi_db"."bi_aggregate_state_event" ON CLUSTER '{cluster}'
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"VIEW","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';
-- bi.aggregate.statePublic --
-- bi.aggregate.commandIngress --
CREATE TABLE "bi_db_consumer"."bi_aggregate_command_queue" ON CLUSTER '{cluster}'
("data" String)
ENGINE = Kafka('localhost:9093', 'wow.bi.aggregate.command',
               'wow-bi.4293daaf07b6609833555520ba03033b.bi_aggregate_command_consumer', 'JSONAsString')
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"QUEUE","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE MATERIALIZED VIEW "bi_db_consumer"."bi_aggregate_command_consumer" ON CLUSTER '{cluster}'
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"CONSUMER","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';
-- bi.aggregate.commandIngress --
-- bi.aggregate.stateIngress --
CREATE TABLE "bi_db_consumer"."bi_aggregate_state_queue" ON CLUSTER '{cluster}'
(
    "data" String
) ENGINE = Kafka('localhost:9093', 'wow.bi.aggregate.state',
                 'wow-bi.4293daaf07b6609833555520ba03033b.bi_aggregate_state_consumer', 'JSONAsString')
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"QUEUE","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';

CREATE MATERIALIZED VIEW "bi_db_consumer"."bi_aggregate_state_consumer" ON CLUSTER '{cluster}'
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","aggregate":"bi.aggregate","kind":"CONSUMER","consumerIdentity":"4293daaf07b6609833555520ba03033b"}';
-- bi.aggregate.stateIngress --
-- ownership-registry-confirmation --
            INSERT INTO "bi_db_consumer"."__wow_bi_registry_82ea723bc0a7d5fe1a1f3dfcfd696fd4"
            ("deployment_id", "row_kind",
             "object_database", "object_name", "kind",
             "aggregate", "consumer_identity",
             "definition_fingerprint", "revision", "status",
             "row_fingerprint")
            VALUES
            ('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'HEAD', '', '', 'ANCHOR', NULL, NULL, 'bea8eff79d2387dc0cd0a96cc71327d3', 42, 'ACTIVE', 'bea8eff79d2387dc0cd0a96cc71327d3'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_command', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'e3ca93221fb69e69307397d550596dde', 22, 'ACTIVE', '4112c38bfa8fd8db0a46bf700f5a2c3b'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_command_store', 'STORE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '19f1974c0a6a4adbfa537cc62c184c49', 23, 'ACTIVE', '1660125ba5bddfcd1227b65d5fe740a9'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_command_store_local', 'STORE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'e449b5eba702a027ec086eb5341193f0', 24, 'ACTIVE', '5934fa76b202e69634bbc095db333b02'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '3b3547863c66bb4cfd019706fb95b7cf', 25, 'ACTIVE', '1c1fff733ae96d78b834087323b25c83'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_event', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'fea61ceb1eb904cabb5a06771fc000c3', 26, 'ACTIVE', 'b6439b4f9af18dd46bdc31f454374db0'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'fcd6e180c2e46af865b8b2c7ef508a29', 27, 'ACTIVE', '8cc48b46f2e1ac96cf7e89d7752ddd6a'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '8dc2846436332dbf2b28b6c9a292feda', 28, 'ACTIVE', 'dea984e41c0389e9214965ded4180599'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_items', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '5288161b7e2e79c86bde4625a5ee3146', 29, 'ACTIVE', 'f9d3277274af1db4669e3c5cea8ce8a6'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_like_list_item', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'b9cc60b7579fe160946edef5de09546a', 30, 'ACTIVE', '4ece3103af394e412c65f929e3a32831'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_nested_list', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'ab350eb9bd5fd2d6cca4da415536f1e4', 31, 'ACTIVE', 'dbd22d8b9350b3c3b8176956fc5b74a3'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_nested_list_list', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'db37b35ab8457dea1f6e297c08ff4f42', 32, 'ACTIVE', '4870a9a2e593870b3bd194774799614d'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_root_set', 'VIEW', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'f025fbc43ac309d7476466879132d692', 33, 'ACTIVE', 'bfb88bc96eebc10891671da7598ce70d'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_store', 'STORE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'bd592ff1dac6203316f97f39f6c85c99', 34, 'ACTIVE', 'c7c2067f5944eb77a9f9fddb883a4e4b'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_last_store_local', 'STORE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'a1dc9b4a073719d0a994079e667e39b8', 35, 'ACTIVE', '0eada9a53c9cc6c20b179f142da99f87'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_store', 'STORE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'cca42da69a71bcf60b13328448de10f5', 36, 'ACTIVE', '4e1fb7838951df2d0fb1d46ccf1d15e0'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db', 'bi_aggregate_state_store_local', 'STORE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '7fec1b46d373f8edf723361151c04c2e', 37, 'ACTIVE', 'b96e76345c927530fac88d0516177ef7'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_command_consumer', 'CONSUMER', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', 'd0b7e4ae55416fc74121b0e5b51fa8ef', 38, 'ACTIVE', 'fed2c0b18dcfb76978e2edf04e7796b8'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_command_queue', 'QUEUE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '70bbe76ac011a77178f47a8c903c969e', 39, 'ACTIVE', 'f533fe5289f8cae3d62cfab3ef56dc73'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_state_consumer', 'CONSUMER', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '3727156e7faf5b4d5fec45de245d7eec', 40, 'ACTIVE', '1bf1c76fa430ef8928a2849ffe7076b8'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_state_last_consumer', 'CONSUMER', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '9ec74f00d27a11e981f37d97bcb60d61', 41, 'ACTIVE', '6e4dcf60c15190a7b987dff6e517301e'),
('82ea723bc0a7d5fe1a1f3dfcfd696fd4', 'OBJECT', 'bi_db_consumer', 'bi_aggregate_state_queue', 'QUEUE', 'bi.aggregate', '4293daaf07b6609833555520ba03033b', '537ddfef61d8614c6a2664da809e2662', 42, 'ACTIVE', 'f7157e2464fe133be3e25935ad28d8a0');
-- ownership-registry-confirmation --
-- deployment-anchor --
CREATE VIEW "bi_db_consumer"."__wow_bi_deployment" ON CLUSTER '{cluster}' AS (SELECT 1 AS "alive" WHERE 0) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"82ea723bc0a7d5fe1a1f3dfcfd696fd4","configurationFingerprint":"4293daaf07b6609833555520ba03033b","topologyFingerprint":"1a217ccdf2c6535f9f8bb381c79fd1d1","kind":"ANCHOR","consumerIdentity":"4293daaf07b6609833555520ba03033b","registryRevision":42}';
-- deployment-anchor --
