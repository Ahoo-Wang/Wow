-- operation: Deploy, destructive: false
-- diagnostic: ORPHANED_DATA_TABLE DATA_TABLE_RETAINED bi-it.golden_sibling lifecycle.reconcile
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
            ('623dfbee6748cde26fc115e6da93e2e5', 'HEAD', '', '', 'ANCHOR', NULL, NULL, '29806c2aae8e3b246e3e934d44d120d9', 76, 'ACTIVE', '29806c2aae8e3b246e3e934d44d120d9'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_command', 'VIEW', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'cb8b8da0470af202e65c083b5b149dfc', 63, 'PENDING_DROP', 'f60705707d76fee250f69ec6864e4504'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_command_store', 'STORE', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', '3965804441d1dc4d5f3dcde5a5c010c6', 64, 'RETIRED', 'a19f9ca0c9f0f8013a19ec3f2d855a29'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state', 'VIEW', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'edaac4ee36943c04f2aefba6404bf8d2', 65, 'PENDING_DROP', 'a65d1a4d7632a34764cd9b0052464ab1'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_event', 'VIEW', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'ac5a7dfad34d2904c249899c2b97b114', 66, 'PENDING_DROP', 'c9fc54434e0715755b5a680e7b9f3e0f'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last', 'VIEW', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', '026c6e3b8efd1a46fce46463d4a63c44', 67, 'PENDING_DROP', '602279b456d79dacbabea2df9e2f1fa3'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_root', 'VIEW', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'c70daeafdde9d3b943b043f6d9faf4f3', 68, 'PENDING_DROP', 'e0cd54f0943f6d108dab7b021d48dbf8'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_root_items', 'VIEW', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'aacd88598ee643a5a8cbb442224723c3', 69, 'PENDING_DROP', '860f7f605ab1b164844ddc857c3b0a2b'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_store', 'STORE', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', '5ce08e65c9e2f648c23cf6ef5739d698', 70, 'RETIRED', '46d04e0c9ad925d974783d8bd33db24c'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_store', 'STORE', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'b2ca3633e2d4e12acdb38aac2bb6ee46', 71, 'RETIRED', 'ec12dc6bbe5b95148e22d7e21f8f2e87'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', 'aeaaf818bcec879f2b413ec9614c99d7', 18, 'ACTIVE', '44397c31243da3d65cc43e98b5fef2fe'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command_store', 'STORE', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '091091716cfc4a3c60acb7c27b150237', 19, 'ACTIVE', 'd4dac9523afcaa58c2ee5cf1cb425729'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '93eea4c3dc6af2e929af09f6f04b107f', 20, 'ACTIVE', '86dbdd29a0d56b09da02dede32ee4a56'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_event', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '4479975a15bca892ebe4e3f79fc0094b', 21, 'ACTIVE', '2b5e482d2c6f3864c0a3826ecff05eef'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '442e00039e3b093bf5cdcfd0d91faf35', 22, 'ACTIVE', '9386e2d7d65d8c3ae4264ca91c4e2769'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', 'c85735b9b05ffd65aa71ab7233c2562c', 23, 'ACTIVE', 'daf5d8395a9130559a9bc128458a9898'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_field_7dc34f9338e4d4c34ea6d6664feeaad1', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '4650def40781fa890c1d8c7d556a67fb', 24, 'ACTIVE', 'd14d0f0a4c84a40beab764dcf7770383'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_nullable_objects', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', 'f068d5dc2be4dddc981ec8f5dc169c1b', 25, 'ACTIVE', '44c89ceb56acc1638920640371a1184e'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_recovery_items', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '19440df1a7a7118d81f37afad9fb201b', 26, 'ACTIVE', '4a20461be01f5d05e45b19a8e4a0b383'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_recovery_items_children', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '1f21a820fc88f490be62a440b436f3b4', 27, 'ACTIVE', '36104fd325bef391a913120b85c538e0'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_store', 'STORE', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', 'ac96f6cc9e1e6c53571a584223011c2c', 28, 'ACTIVE', 'b7a1ddba6d4f2e40bcc688e8c00d2393'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_store', 'STORE', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '2b3159a20eca2f902a2bb40d0dbcafd3', 29, 'ACTIVE', 'e87a8676c8fba443eb962860a256e497'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_command_consumer', 'CONSUMER', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', '17d1402bcd016eccb8cc6f90c9b1695f', 72, 'PENDING_DROP', '47ee563ac894859366ca9b8a907930b7'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_command_queue', 'QUEUE', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', '46afd4b19d4d591e22ee839675621335', 73, 'PENDING_DROP', '2585232c25de03786c92ab5b718b9cf0'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_state_consumer', 'CONSUMER', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', '4d024af58832675b8ff04734a41897b5', 74, 'PENDING_DROP', '30e54544a219c6052457c1905fd93a8d'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_state_last_consumer', 'CONSUMER', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'fcb0ee490c402ad16aa685c50317c9b5', 75, 'PENDING_DROP', '0e9439eea6809bbdc358c529dc4b4dce'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_state_queue', 'QUEUE', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'd3df8f8e65ec5c15e7c59379db4110fc', 76, 'PENDING_DROP', '9c46fbfbbd8babd5145e4be5d454460b'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_command_consumer', 'CONSUMER', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', 'c0efe90ecd1c4c744ebfd30dd294afcb', 30, 'ACTIVE', '1a43d3e177b4c436288781c3ae56b415'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_command_queue', 'QUEUE', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '8fed9ad29d345b026bd4de035dad1490', 31, 'ACTIVE', '550d4ead41b4fdff1214c2d19a248c49'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_consumer', 'CONSUMER', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', 'f6204886a8f2618c855db05e7229324d', 32, 'ACTIVE', '8ea8b44a6bc2453dbe4eb2b9500b99e9'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_last_consumer', 'CONSUMER', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '0a9f87a4f0cabc21a85eaa1b6caa4cb9', 33, 'ACTIVE', '1734d6056cfc297c3dcaaed124ca0c6f'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_queue', 'QUEUE', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '778ad3a17ebd5fddeccda98237030ccb', 34, 'ACTIVE', '4418eb97bb977e4e3d4cc84be2fd281a');
-- ownership-registry-intent --
-- lifecycle --
-- bi-it.nullable.pause-ingress --
-- bi-it.nullable.pause-ingress --
-- reconcile-observed-catalog --
DROP VIEW IF EXISTS "bi_golden"."bi_it_golden_sibling_state_last_root_items" SYNC;

DROP VIEW IF EXISTS "bi_golden_consumer"."bi_it_golden_sibling_state_last_consumer" SYNC;

DROP VIEW IF EXISTS "bi_golden_consumer"."bi_it_golden_sibling_command_consumer" SYNC;

DROP VIEW IF EXISTS "bi_golden"."bi_it_golden_sibling_state_last_root" SYNC;

DROP VIEW IF EXISTS "bi_golden_consumer"."bi_it_golden_sibling_state_consumer" SYNC;

DROP TABLE IF EXISTS "bi_golden_consumer"."bi_it_golden_sibling_command_queue" SYNC;

DROP VIEW IF EXISTS "bi_golden"."bi_it_golden_sibling_state_event" SYNC;

DROP TABLE IF EXISTS "bi_golden_consumer"."bi_it_golden_sibling_state_queue" SYNC;

DROP VIEW IF EXISTS "bi_golden"."bi_it_golden_sibling_state_last" SYNC;

DROP VIEW IF EXISTS "bi_golden"."bi_it_golden_sibling_command" SYNC;

DROP VIEW IF EXISTS "bi_golden"."bi_it_golden_sibling_state" SYNC;
-- reconcile-observed-catalog --
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
  COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"STORE","consumerIdentity":"04e164f980daedb379f00846a78ae7d1"}';
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
      COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"STORE","consumerIdentity":"04e164f980daedb379f00846a78ae7d1"}';
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
      COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"STORE","consumerIdentity":"04e164f980daedb379f00846a78ae7d1"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last"
AS (SELECT * FROM "bi_golden"."bi_it_nullable_state_last_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"04e164f980daedb379f00846a78ae7d1"}';
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"04e164f980daedb379f00846a78ae7d1"}';

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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"04e164f980daedb379f00846a78ae7d1"}';

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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"04e164f980daedb379f00846a78ae7d1"}';

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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"04e164f980daedb379f00846a78ae7d1"}';

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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"04e164f980daedb379f00846a78ae7d1"}';
-- bi-it.nullable.expansion --
-- bi-it.nullable.commandPublic --
CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_command"
AS (SELECT * FROM "bi_golden"."bi_it_nullable_command_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"04e164f980daedb379f00846a78ae7d1"}';
-- bi-it.nullable.commandPublic --
-- bi-it.nullable.statePublic --
CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state"
AS (SELECT * FROM "bi_golden"."bi_it_nullable_state_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"04e164f980daedb379f00846a78ae7d1"}';

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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"04e164f980daedb379f00846a78ae7d1"}';
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
            ('623dfbee6748cde26fc115e6da93e2e5', 'HEAD', '', '', 'ANCHOR', NULL, NULL, '1fa354fc3dd977a61a0e6649a9b085a5', 87, 'ACTIVE', '1fa354fc3dd977a61a0e6649a9b085a5'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_command', 'VIEW', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'cb8b8da0470af202e65c083b5b149dfc', 77, 'TOMBSTONE', '06faf8c60a519d04ff513963bcc7b92f'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_command_store', 'STORE', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', '3965804441d1dc4d5f3dcde5a5c010c6', 64, 'RETIRED', 'a19f9ca0c9f0f8013a19ec3f2d855a29'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state', 'VIEW', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'edaac4ee36943c04f2aefba6404bf8d2', 78, 'TOMBSTONE', 'e50296c1fa98151b9b726bfb16ad2fe7'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_event', 'VIEW', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'ac5a7dfad34d2904c249899c2b97b114', 79, 'TOMBSTONE', 'dc15c41e79c3067c85216727a5fc8cdd'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last', 'VIEW', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', '026c6e3b8efd1a46fce46463d4a63c44', 80, 'TOMBSTONE', '5edd28e4e874836b5637feca8b55c3de'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_root', 'VIEW', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'c70daeafdde9d3b943b043f6d9faf4f3', 81, 'TOMBSTONE', '62cdbfeef2e1e657609c205edeafc134'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_root_items', 'VIEW', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'aacd88598ee643a5a8cbb442224723c3', 82, 'TOMBSTONE', 'a2f3c9ec942392a50a6d8629ea766e09'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_store', 'STORE', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', '5ce08e65c9e2f648c23cf6ef5739d698', 70, 'RETIRED', '46d04e0c9ad925d974783d8bd33db24c'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_store', 'STORE', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'b2ca3633e2d4e12acdb38aac2bb6ee46', 71, 'RETIRED', 'ec12dc6bbe5b95148e22d7e21f8f2e87'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', 'aeaaf818bcec879f2b413ec9614c99d7', 18, 'ACTIVE', '44397c31243da3d65cc43e98b5fef2fe'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command_store', 'STORE', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '091091716cfc4a3c60acb7c27b150237', 19, 'ACTIVE', 'd4dac9523afcaa58c2ee5cf1cb425729'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '93eea4c3dc6af2e929af09f6f04b107f', 20, 'ACTIVE', '86dbdd29a0d56b09da02dede32ee4a56'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_event', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '4479975a15bca892ebe4e3f79fc0094b', 21, 'ACTIVE', '2b5e482d2c6f3864c0a3826ecff05eef'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '442e00039e3b093bf5cdcfd0d91faf35', 22, 'ACTIVE', '9386e2d7d65d8c3ae4264ca91c4e2769'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', 'c85735b9b05ffd65aa71ab7233c2562c', 23, 'ACTIVE', 'daf5d8395a9130559a9bc128458a9898'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_field_7dc34f9338e4d4c34ea6d6664feeaad1', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '4650def40781fa890c1d8c7d556a67fb', 24, 'ACTIVE', 'd14d0f0a4c84a40beab764dcf7770383'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_nullable_objects', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', 'f068d5dc2be4dddc981ec8f5dc169c1b', 25, 'ACTIVE', '44c89ceb56acc1638920640371a1184e'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_recovery_items', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '19440df1a7a7118d81f37afad9fb201b', 26, 'ACTIVE', '4a20461be01f5d05e45b19a8e4a0b383'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_recovery_items_children', 'VIEW', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '1f21a820fc88f490be62a440b436f3b4', 27, 'ACTIVE', '36104fd325bef391a913120b85c538e0'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_store', 'STORE', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', 'ac96f6cc9e1e6c53571a584223011c2c', 28, 'ACTIVE', 'b7a1ddba6d4f2e40bcc688e8c00d2393'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_store', 'STORE', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '2b3159a20eca2f902a2bb40d0dbcafd3', 29, 'ACTIVE', 'e87a8676c8fba443eb962860a256e497'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_command_consumer', 'CONSUMER', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', '17d1402bcd016eccb8cc6f90c9b1695f', 83, 'TOMBSTONE', 'b3d5e2d5a6c6d006a8da8de1838c78ef'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_command_queue', 'QUEUE', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', '46afd4b19d4d591e22ee839675621335', 84, 'TOMBSTONE', '6d7318913c2a268a3d739de451908c2b'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_state_consumer', 'CONSUMER', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', '4d024af58832675b8ff04734a41897b5', 85, 'TOMBSTONE', 'ae1825c140c216eece1978272dcb7aaa'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_state_last_consumer', 'CONSUMER', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'fcb0ee490c402ad16aa685c50317c9b5', 86, 'TOMBSTONE', 'b03525b8103aa5044095fcdd2b2ada98'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_state_queue', 'QUEUE', 'bi-it.golden_sibling', '04e164f980daedb379f00846a78ae7d1', 'd3df8f8e65ec5c15e7c59379db4110fc', 87, 'TOMBSTONE', '913f92904adbbd339741f12355701e51'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_command_consumer', 'CONSUMER', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', 'c0efe90ecd1c4c744ebfd30dd294afcb', 30, 'ACTIVE', '1a43d3e177b4c436288781c3ae56b415'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_command_queue', 'QUEUE', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '8fed9ad29d345b026bd4de035dad1490', 31, 'ACTIVE', '550d4ead41b4fdff1214c2d19a248c49'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_consumer', 'CONSUMER', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', 'f6204886a8f2618c855db05e7229324d', 32, 'ACTIVE', '8ea8b44a6bc2453dbe4eb2b9500b99e9'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_last_consumer', 'CONSUMER', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '0a9f87a4f0cabc21a85eaa1b6caa4cb9', 33, 'ACTIVE', '1734d6056cfc297c3dcaaed124ca0c6f'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_queue', 'QUEUE', 'bi-it.nullable', '04e164f980daedb379f00846a78ae7d1', '778ad3a17ebd5fddeccda98237030ccb', 34, 'ACTIVE', '4418eb97bb977e4e3d4cc84be2fd281a');
-- ownership-registry-confirmation --
-- deployment-anchor --
CREATE OR REPLACE VIEW "bi_golden_consumer"."__wow_bi_deployment" AS (SELECT 1 AS "alive" WHERE 0) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","kind":"ANCHOR","consumerIdentity":"04e164f980daedb379f00846a78ae7d1","registryRevision":87}';
-- deployment-anchor --
