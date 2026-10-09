-- operation: Deploy, destructive: false
-- diagnostic: RAW_JSON_FALLBACK RAW_JSON bi-integration-service.nullable a/b~c.amount
-- diagnostic: RAW_JSON_FALLBACK RAW_JSON bi-integration-service.nullable bigDecimal
-- diagnostic: RAW_JSON_FALLBACK RAW_JSON bi-integration-service.nullable bigDecimals
-- diagnostic: RAW_JSON_FALLBACK RAW_JSON bi-integration-service.nullable claimedArrayList
-- diagnostic: RAW_JSON_FALLBACK RAW_JSON bi-integration-service.nullable claimedMap
-- diagnostic: RAW_JSON_FALLBACK RAW_JSON bi-integration-service.nullable mixed
-- diagnostic: RAW_JSON_FALLBACK RAW_JSON bi-integration-service.nullable quote'backslash\line
raw
-- diagnostic: RAW_JSON_FALLBACK RAW_JSON bi-integration-service.nullable recoveryItems.amount
-- diagnostic: RAW_JSON_FALLBACK RAW_JSON bi-integration-service.nullable recoveryItems.children.amount
-- diagnostic: RAW_JSON_FALLBACK RAW_JSON bi-integration-service.nullable shadow.quote'backslash\line
raw
-- global --
CREATE DATABASE IF NOT EXISTS "bi_golden";

CREATE DATABASE IF NOT EXISTS "bi_golden_consumer";
-- global --
-- ownership-registry --
CREATE TABLE IF NOT EXISTS "bi_golden_consumer"."__wow_bi_registry_623dfbee6748cde26fc115e6da93e2e5"
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
  COMMENT 'wow-bi-registry:623dfbee6748cde26fc115e6da93e2e5';
-- ownership-registry --
-- ownership-registry-intent --
            INSERT INTO "bi_golden_consumer"."__wow_bi_registry_623dfbee6748cde26fc115e6da93e2e5"
            ("deployment_id", "row_kind",
             "object_database", "object_name", "kind",
             "aggregate", "consumer_identity",
             "definition_fingerprint", "revision", "status",
             "row_fingerprint")
            VALUES
            ('623dfbee6748cde26fc115e6da93e2e5', 'HEAD', '', '', 'ANCHOR', NULL, NULL, '0000000000000000000000000000eeee', 17, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', 'aeaaf818bcec879f2b413ec9614c99d7', 1, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command_store', 'STORE', 'bi-it.nullable', '0000000000000000000000000000ffff', '091091716cfc4a3c60acb7c27b150237', 2, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '93eea4c3dc6af2e929af09f6f04b107f', 3, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_event', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '4479975a15bca892ebe4e3f79fc0094b', 4, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '442e00039e3b093bf5cdcfd0d91faf35', 5, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', 'c85735b9b05ffd65aa71ab7233c2562c', 6, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_field_7dc34f9338e4d4c34ea6d6664feeaad1', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '4650def40781fa890c1d8c7d556a67fb', 7, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_nullable_objects', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', 'f068d5dc2be4dddc981ec8f5dc169c1b', 8, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_recovery_items', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '19440df1a7a7118d81f37afad9fb201b', 9, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_recovery_items_children', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '1f21a820fc88f490be62a440b436f3b4', 10, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_store', 'STORE', 'bi-it.nullable', '0000000000000000000000000000ffff', 'ac96f6cc9e1e6c53571a584223011c2c', 11, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_store', 'STORE', 'bi-it.nullable', '0000000000000000000000000000ffff', '2b3159a20eca2f902a2bb40d0dbcafd3', 12, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_command_consumer', 'CONSUMER', 'bi-it.nullable', '0000000000000000000000000000ffff', 'c0efe90ecd1c4c744ebfd30dd294afcb', 13, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_command_queue', 'QUEUE', 'bi-it.nullable', '0000000000000000000000000000ffff', '8fed9ad29d345b026bd4de035dad1490', 14, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_consumer', 'CONSUMER', 'bi-it.nullable', '0000000000000000000000000000ffff', 'f6204886a8f2618c855db05e7229324d', 15, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_last_consumer', 'CONSUMER', 'bi-it.nullable', '0000000000000000000000000000ffff', '0a9f87a4f0cabc21a85eaa1b6caa4cb9', 16, 'PENDING_CREATE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_queue', 'QUEUE', 'bi-it.nullable', '0000000000000000000000000000ffff', '778ad3a17ebd5fddeccda98237030ccb', 17, 'PENDING_CREATE', '0000000000000000000000000000eeee');
-- ownership-registry-intent --
-- lifecycle --
-- bi-it.nullable.pause-ingress --
-- bi-it.nullable.pause-ingress --
-- lifecycle --
-- bi-it.nullable.commandStorage --
CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_nullable_command_store"
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
    "create_time" DateTime64(3, 'UTC')
) ENGINE = ReplacingMergeTree
  PARTITION BY toYYYYMM("create_time")
  ORDER BY "id"
  COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"STORE","consumerIdentity":"0000000000000000000000000000ffff"}';
-- bi-it.nullable.commandStorage --
-- bi-it.nullable.stateStorage --
CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_nullable_state_store"
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
    "first_event_time" DateTime64(3, 'UTC'),
    "create_time" DateTime64(3, 'UTC'),
    "tags" Map(String, Array(String)),
    "deleted" Bool
) ENGINE = ReplacingMergeTree("version")
      PARTITION BY toYYYYMM("create_time")
      ORDER BY ("tenant_id", "aggregate_id", "version")
      COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"STORE","consumerIdentity":"0000000000000000000000000000ffff"}';
-- bi-it.nullable.stateStorage --
-- bi-it.nullable.stateLast --
CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_nullable_state_last_store"
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
    "first_event_time" DateTime64(3, 'UTC'),
    "create_time" DateTime64(3, 'UTC'),
    "tags" Map(String, Array(String)),
    "deleted" Bool
) ENGINE = ReplacingMergeTree("version")
      PARTITION BY toYYYYMM("first_event_time")
      ORDER BY ("tenant_id", "aggregate_id")
      COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"STORE","consumerIdentity":"0000000000000000000000000000ffff"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last"
AS (SELECT * FROM "bi_golden"."bi_it_nullable_state_last_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"0000000000000000000000000000ffff"}';
-- bi-it.nullable.stateLast --
-- bi-it.nullable.expansion --
CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last_root" AS (
WITH
JSONExtractRaw("__source"."state", 'nullableObject') AS "nullable_object",
JSONExtractRaw("__source"."state", 'shadow') AS "shadow"
SELECT
JSONExtractRaw("__source"."state", 'nullableArray') AS "__raw__nullable_array",
JSONExtractRaw("__source"."state", 'nullableMap') AS "__raw__nullable_map",
JSONExtractRaw("__source"."state", 'nullableObject') AS "__raw__nullable_object",
JSONExtractRaw("__source"."state", 'nullableObjects') AS "__raw__nullable_objects",
JSONExtractRaw("__source"."state", 'nullableScalar') AS "__raw__nullable_scalar",
JSONExtractArrayRaw("__source"."state", 'a/b~c') AS "a/b~c",
JSONExtractRaw("__source"."state", 'bigDecimal') AS "big_decimal",
JSONExtractRaw("__source"."state", 'bigDecimals') AS "big_decimals",
JSONExtractRaw("__source"."state", 'claimedArrayList') AS "claimed_array_list",
JSONExtractRaw("__source"."state", 'claimedMap') AS "claimed_map",
JSONExtract("__source"."state", 'date', 'String') AS "date",
JSONExtract("__source"."state", 'dates', 'Array(String)') AS "dates",
JSONExtract("__source"."state", 'duration', 'String') AS "duration",
JSONExtract("__source"."state", 'durations', 'Array(String)') AS "durations",
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtract("__source"."state", 'instant', 'String') AS "instant",
JSONExtract("__source"."state", 'instants', 'Map(String, String)') AS "instants",
JSONExtract("__source"."state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtractRaw("__source"."state", 'mixed') AS "mixed",
JSONExtract("__source"."state", 'nullableArray', 'Array(Nullable(Int32))') AS "nullable_array",
JSONExtract("__source"."state", 'nullableMap', 'Map(String, Nullable(Int32))') AS "nullable_map",
JSONExtract("nullable_object", 'value', 'Nullable(Int32)') AS "nullable_object__value",
JSONExtractArrayRaw("__source"."state", 'nullableObjects') AS "nullable_objects",
JSONExtract("__source"."state", 'nullableScalar', 'Nullable(Int32)') AS "nullable_scalar",
JSONExtractRaw("__source"."state", 'quote''backslash\\line\x0Araw') AS "quote'backslash\\line\x0Araw",
JSONExtractArrayRaw("__source"."state", 'recoveryItems') AS "recovery_items",
JSONExtractRaw("shadow", 'quote''backslash\\line\x0Araw') AS "shadow__quote'backslash\\line\x0Araw",
JSONExtract("__source"."state", 'specialDouble', 'Float64') AS "special_double",
JSONExtract("__source"."state", 'specialDoubles', 'Array(Float64)') AS "special_doubles",
JSONExtract("__source"."state", 'specialFloat', 'Float32') AS "special_float",
JSONExtract("__source"."state", 'sqlDate', 'String') AS "sql_date",
JSONExtract("__source"."state", 'uuid', 'UUID') AS "uuid",
JSONExtract("__source"."state", 'year', 'Int32') AS "year",
JSONExtract("__source"."state", 'years', 'Map(String, Int32)') AS "years",
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
FROM "bi_golden"."bi_it_nullable_state_last" AS "__source"
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"0000000000000000000000000000ffff"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last_root_field_7dc34f9338e4d4c34ea6d6664feeaad1" AS (
WITH
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'a/b~c')),
                   JSONExtractArrayRaw("__source"."state", 'a/b~c'))) AS "__cursor__a/b~c",
tupleElement("__cursor__a/b~c", 2) AS "a/b~c",
JSONExtractRaw("__source"."state", 'nullableObject') AS "nullable_object",
JSONExtractRaw("__source"."state", 'shadow') AS "shadow"
SELECT
"a/b~c" AS "__raw__a/b~c",
JSONExtractRaw("__source"."state", 'nullableArray') AS "__raw__nullable_array",
JSONExtractRaw("__source"."state", 'nullableMap') AS "__raw__nullable_map",
JSONExtractRaw("__source"."state", 'nullableObject') AS "__raw__nullable_object",
JSONExtractRaw("__source"."state", 'nullableScalar') AS "__raw__nullable_scalar",
JSONExtractRaw("a/b~c", 'amount') AS "a/b~c__amount",
JSONExtractRaw("__source"."state", 'bigDecimal') AS "big_decimal",
JSONExtractRaw("__source"."state", 'bigDecimals') AS "big_decimals",
JSONExtractRaw("__source"."state", 'claimedArrayList') AS "claimed_array_list",
JSONExtractRaw("__source"."state", 'claimedMap') AS "claimed_map",
JSONExtract("__source"."state", 'date', 'String') AS "date",
JSONExtract("__source"."state", 'dates', 'Array(String)') AS "dates",
JSONExtract("__source"."state", 'duration', 'String') AS "duration",
JSONExtract("__source"."state", 'durations', 'Array(String)') AS "durations",
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtract("__source"."state", 'instant', 'String') AS "instant",
JSONExtract("__source"."state", 'instants', 'Map(String, String)') AS "instants",
JSONExtract("__source"."state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtractRaw("__source"."state", 'mixed') AS "mixed",
JSONExtract("__source"."state", 'nullableArray', 'Array(Nullable(Int32))') AS "nullable_array",
JSONExtract("__source"."state", 'nullableMap', 'Map(String, Nullable(Int32))') AS "nullable_map",
JSONExtract("nullable_object", 'value', 'Nullable(Int32)') AS "nullable_object__value",
JSONExtract("__source"."state", 'nullableScalar', 'Nullable(Int32)') AS "nullable_scalar",
JSONExtractRaw("__source"."state", 'quote''backslash\\line\x0Araw') AS "quote'backslash\\line\x0Araw",
JSONExtractRaw("shadow", 'quote''backslash\\line\x0Araw') AS "shadow__quote'backslash\\line\x0Araw",
JSONExtract("__source"."state", 'specialDouble', 'Float64') AS "special_double",
JSONExtract("__source"."state", 'specialDoubles', 'Array(Float64)') AS "special_doubles",
JSONExtract("__source"."state", 'specialFloat', 'Float32') AS "special_float",
JSONExtract("__source"."state", 'sqlDate', 'String') AS "sql_date",
JSONExtract("__source"."state", 'uuid', 'UUID') AS "uuid",
JSONExtract("__source"."state", 'year', 'Int32') AS "year",
JSONExtract("__source"."state", 'years', 'Map(String, Int32)') AS "years",
"__source"."state" AS "__state",
toUInt64(tupleElement("__cursor__a/b~c", 1) - 1) AS "__index",
concat('/a~1b~0c/', toString(tupleElement("__cursor__a/b~c", 1) - 1)) AS "__path",
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
FROM "bi_golden"."bi_it_nullable_state_last" AS "__source"
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"0000000000000000000000000000ffff"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last_root_nullable_objects" AS (
WITH
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'nullableObjects')),
                   JSONExtractArrayRaw("__source"."state", 'nullableObjects'))) AS "__cursor__nullable_objects",
tupleElement("__cursor__nullable_objects", 2) AS "nullable_objects",
JSONExtractRaw("__source"."state", 'nullableObject') AS "nullable_object",
JSONExtractRaw("__source"."state", 'shadow') AS "shadow"
SELECT
JSONExtractRaw("__source"."state", 'nullableArray') AS "__raw__nullable_array",
JSONExtractRaw("__source"."state", 'nullableMap') AS "__raw__nullable_map",
JSONExtractRaw("__source"."state", 'nullableObject') AS "__raw__nullable_object",
"nullable_objects" AS "__raw__nullable_objects",
JSONExtractRaw("__source"."state", 'nullableScalar') AS "__raw__nullable_scalar",
JSONExtractRaw("__source"."state", 'bigDecimal') AS "big_decimal",
JSONExtractRaw("__source"."state", 'bigDecimals') AS "big_decimals",
JSONExtractRaw("__source"."state", 'claimedArrayList') AS "claimed_array_list",
JSONExtractRaw("__source"."state", 'claimedMap') AS "claimed_map",
JSONExtract("__source"."state", 'date', 'String') AS "date",
JSONExtract("__source"."state", 'dates', 'Array(String)') AS "dates",
JSONExtract("__source"."state", 'duration', 'String') AS "duration",
JSONExtract("__source"."state", 'durations', 'Array(String)') AS "durations",
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtract("__source"."state", 'instant', 'String') AS "instant",
JSONExtract("__source"."state", 'instants', 'Map(String, String)') AS "instants",
JSONExtract("__source"."state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtractRaw("__source"."state", 'mixed') AS "mixed",
JSONExtract("__source"."state", 'nullableArray', 'Array(Nullable(Int32))') AS "nullable_array",
JSONExtract("__source"."state", 'nullableMap', 'Map(String, Nullable(Int32))') AS "nullable_map",
JSONExtract("nullable_object", 'value', 'Nullable(Int32)') AS "nullable_object__value",
JSONExtract("nullable_objects", 'value', 'Nullable(Int32)') AS "nullable_objects__value",
JSONExtract("__source"."state", 'nullableScalar', 'Nullable(Int32)') AS "nullable_scalar",
JSONExtractRaw("__source"."state", 'quote''backslash\\line\x0Araw') AS "quote'backslash\\line\x0Araw",
JSONExtractRaw("shadow", 'quote''backslash\\line\x0Araw') AS "shadow__quote'backslash\\line\x0Araw",
JSONExtract("__source"."state", 'specialDouble', 'Float64') AS "special_double",
JSONExtract("__source"."state", 'specialDoubles', 'Array(Float64)') AS "special_doubles",
JSONExtract("__source"."state", 'specialFloat', 'Float32') AS "special_float",
JSONExtract("__source"."state", 'sqlDate', 'String') AS "sql_date",
JSONExtract("__source"."state", 'uuid', 'UUID') AS "uuid",
JSONExtract("__source"."state", 'year', 'Int32') AS "year",
JSONExtract("__source"."state", 'years', 'Map(String, Int32)') AS "years",
"__source"."state" AS "__state",
toUInt64(tupleElement("__cursor__nullable_objects", 1) - 1) AS "__index",
concat('/nullableObjects/', toString(tupleElement("__cursor__nullable_objects", 1) - 1)) AS "__path",
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
FROM "bi_golden"."bi_it_nullable_state_last" AS "__source"
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"0000000000000000000000000000ffff"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last_root_recovery_items" AS (
WITH
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'recoveryItems')),
                   JSONExtractArrayRaw("__source"."state", 'recoveryItems'))) AS "__cursor__recovery_items",
tupleElement("__cursor__recovery_items", 2) AS "recovery_items",
JSONExtractRaw("__source"."state", 'nullableObject') AS "nullable_object",
JSONExtractRaw("__source"."state", 'shadow') AS "shadow"
SELECT
JSONExtractRaw("__source"."state", 'nullableArray') AS "__raw__nullable_array",
JSONExtractRaw("__source"."state", 'nullableMap') AS "__raw__nullable_map",
JSONExtractRaw("__source"."state", 'nullableObject') AS "__raw__nullable_object",
JSONExtractRaw("__source"."state", 'nullableScalar') AS "__raw__nullable_scalar",
"recovery_items" AS "__raw__recovery_items",
JSONExtractRaw("recovery_items", 'children') AS "__raw__recovery_items__children",
JSONExtractRaw("__source"."state", 'bigDecimal') AS "big_decimal",
JSONExtractRaw("__source"."state", 'bigDecimals') AS "big_decimals",
JSONExtractRaw("__source"."state", 'claimedArrayList') AS "claimed_array_list",
JSONExtractRaw("__source"."state", 'claimedMap') AS "claimed_map",
JSONExtract("__source"."state", 'date', 'String') AS "date",
JSONExtract("__source"."state", 'dates', 'Array(String)') AS "dates",
JSONExtract("__source"."state", 'duration', 'String') AS "duration",
JSONExtract("__source"."state", 'durations', 'Array(String)') AS "durations",
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtract("__source"."state", 'instant', 'String') AS "instant",
JSONExtract("__source"."state", 'instants', 'Map(String, String)') AS "instants",
JSONExtract("__source"."state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtractRaw("__source"."state", 'mixed') AS "mixed",
JSONExtract("__source"."state", 'nullableArray', 'Array(Nullable(Int32))') AS "nullable_array",
JSONExtract("__source"."state", 'nullableMap', 'Map(String, Nullable(Int32))') AS "nullable_map",
JSONExtract("nullable_object", 'value', 'Nullable(Int32)') AS "nullable_object__value",
JSONExtract("__source"."state", 'nullableScalar', 'Nullable(Int32)') AS "nullable_scalar",
JSONExtractRaw("__source"."state", 'quote''backslash\\line\x0Araw') AS "quote'backslash\\line\x0Araw",
JSONExtractRaw("recovery_items", 'amount') AS "recovery_items__amount",
JSONExtractArrayRaw("recovery_items", 'children') AS "recovery_items__children",
JSONExtractRaw("shadow", 'quote''backslash\\line\x0Araw') AS "shadow__quote'backslash\\line\x0Araw",
JSONExtract("__source"."state", 'specialDouble', 'Float64') AS "special_double",
JSONExtract("__source"."state", 'specialDoubles', 'Array(Float64)') AS "special_doubles",
JSONExtract("__source"."state", 'specialFloat', 'Float32') AS "special_float",
JSONExtract("__source"."state", 'sqlDate', 'String') AS "sql_date",
JSONExtract("__source"."state", 'uuid', 'UUID') AS "uuid",
JSONExtract("__source"."state", 'year', 'Int32') AS "year",
JSONExtract("__source"."state", 'years', 'Map(String, Int32)') AS "years",
"__source"."state" AS "__state",
toUInt64(tupleElement("__cursor__recovery_items", 1) - 1) AS "__index",
concat('/recoveryItems/', toString(tupleElement("__cursor__recovery_items", 1) - 1)) AS "__path",
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
FROM "bi_golden"."bi_it_nullable_state_last" AS "__source"
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"0000000000000000000000000000ffff"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last_root_recovery_items_children" AS (
WITH
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'recoveryItems')),
                   JSONExtractArrayRaw("__source"."state", 'recoveryItems'))) AS "__cursor__recovery_items",
tupleElement("__cursor__recovery_items", 2) AS "recovery_items",
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("recovery_items", 'children')),
                   JSONExtractArrayRaw("recovery_items", 'children'))) AS "__cursor__recovery_items__children",
tupleElement("__cursor__recovery_items__children", 2) AS "recovery_items__children",
JSONExtractRaw("__source"."state", 'nullableObject') AS "nullable_object",
JSONExtractRaw("__source"."state", 'shadow') AS "shadow"
SELECT
JSONExtractRaw("__source"."state", 'nullableArray') AS "__raw__nullable_array",
JSONExtractRaw("__source"."state", 'nullableMap') AS "__raw__nullable_map",
JSONExtractRaw("__source"."state", 'nullableObject') AS "__raw__nullable_object",
JSONExtractRaw("__source"."state", 'nullableScalar') AS "__raw__nullable_scalar",
"recovery_items" AS "__raw__recovery_items",
"recovery_items__children" AS "__raw__recovery_items__children",
JSONExtractRaw("__source"."state", 'bigDecimal') AS "big_decimal",
JSONExtractRaw("__source"."state", 'bigDecimals') AS "big_decimals",
JSONExtractRaw("__source"."state", 'claimedArrayList') AS "claimed_array_list",
JSONExtractRaw("__source"."state", 'claimedMap') AS "claimed_map",
JSONExtract("__source"."state", 'date', 'String') AS "date",
JSONExtract("__source"."state", 'dates', 'Array(String)') AS "dates",
JSONExtract("__source"."state", 'duration', 'String') AS "duration",
JSONExtract("__source"."state", 'durations', 'Array(String)') AS "durations",
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtract("__source"."state", 'instant', 'String') AS "instant",
JSONExtract("__source"."state", 'instants', 'Map(String, String)') AS "instants",
JSONExtract("__source"."state", 'kotlinDuration', 'Int64') AS "kotlin_duration",
JSONExtractRaw("__source"."state", 'mixed') AS "mixed",
JSONExtract("__source"."state", 'nullableArray', 'Array(Nullable(Int32))') AS "nullable_array",
JSONExtract("__source"."state", 'nullableMap', 'Map(String, Nullable(Int32))') AS "nullable_map",
JSONExtract("nullable_object", 'value', 'Nullable(Int32)') AS "nullable_object__value",
JSONExtract("__source"."state", 'nullableScalar', 'Nullable(Int32)') AS "nullable_scalar",
JSONExtractRaw("__source"."state", 'quote''backslash\\line\x0Araw') AS "quote'backslash\\line\x0Araw",
JSONExtractRaw("recovery_items", 'amount') AS "recovery_items__amount",
JSONExtractRaw("recovery_items__children", 'amount') AS "recovery_items__children__amount",
JSONExtractRaw("shadow", 'quote''backslash\\line\x0Araw') AS "shadow__quote'backslash\\line\x0Araw",
JSONExtract("__source"."state", 'specialDouble', 'Float64') AS "special_double",
JSONExtract("__source"."state", 'specialDoubles', 'Array(Float64)') AS "special_doubles",
JSONExtract("__source"."state", 'specialFloat', 'Float32') AS "special_float",
JSONExtract("__source"."state", 'sqlDate', 'String') AS "sql_date",
JSONExtract("__source"."state", 'uuid', 'UUID') AS "uuid",
JSONExtract("__source"."state", 'year', 'Int32') AS "year",
JSONExtract("__source"."state", 'years', 'Map(String, Int32)') AS "years",
"__source"."state" AS "__state",
toUInt64(tupleElement("__cursor__recovery_items__children", 1) - 1) AS "__index",
concat('/recoveryItems/', toString(tupleElement("__cursor__recovery_items", 1) - 1), '/children/', toString(tupleElement("__cursor__recovery_items__children", 1) - 1)) AS "__path",
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
FROM "bi_golden"."bi_it_nullable_state_last" AS "__source"
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"0000000000000000000000000000ffff"}';
-- bi-it.nullable.expansion --
-- bi-it.nullable.commandPublic --
CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_command"
AS (SELECT * FROM "bi_golden"."bi_it_nullable_command_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"0000000000000000000000000000ffff"}';
-- bi-it.nullable.commandPublic --
-- bi-it.nullable.statePublic --
CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state"
AS (SELECT * FROM "bi_golden"."bi_it_nullable_state_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"0000000000000000000000000000ffff"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_event"
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
FROM "bi_golden"."bi_it_nullable_state"
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"0000000000000000000000000000ffff"}';
-- bi-it.nullable.statePublic --
-- bi-it.nullable.commandIngress --
-- bi-it.nullable.commandIngress --
-- bi-it.nullable.stateIngress --
-- bi-it.nullable.stateIngress --
-- ownership-registry-confirmation --
            INSERT INTO "bi_golden_consumer"."__wow_bi_registry_623dfbee6748cde26fc115e6da93e2e5"
            ("deployment_id", "row_kind",
             "object_database", "object_name", "kind",
             "aggregate", "consumer_identity",
             "definition_fingerprint", "revision", "status",
             "row_fingerprint")
            VALUES
            ('623dfbee6748cde26fc115e6da93e2e5', 'HEAD', '', '', 'ANCHOR', NULL, NULL, '0000000000000000000000000000eeee', 34, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 18, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command_store', 'STORE', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 19, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 20, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_event', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 21, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 22, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 23, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_field_7dc34f9338e4d4c34ea6d6664feeaad1', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 24, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_nullable_objects', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 25, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_recovery_items', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 26, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_recovery_items_children', 'VIEW', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 27, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_store', 'STORE', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 28, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_store', 'STORE', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 29, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_command_consumer', 'CONSUMER', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 30, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_command_queue', 'QUEUE', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 31, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_consumer', 'CONSUMER', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 32, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_last_consumer', 'CONSUMER', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 33, 'ACTIVE', '0000000000000000000000000000eeee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_queue', 'QUEUE', 'bi-it.nullable', '0000000000000000000000000000ffff', '0000000000000000000000000000eeee', 34, 'ACTIVE', '0000000000000000000000000000eeee');
-- ownership-registry-confirmation --
-- deployment-anchor --
CREATE OR REPLACE VIEW "bi_golden_consumer"."__wow_bi_deployment" AS (SELECT 1 AS "alive" WHERE 0) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","kind":"ANCHOR","consumerIdentity":"0000000000000000000000000000ffff","registryRevision":34}';
-- deployment-anchor --
