-- operation: Deploy, destructive: false
-- diagnostic: CLUSTER_INTERNAL_REPLICATION_REQUIRED EXTERNAL_CONFIGURATION_REQUIRED * topology.cluster
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
CREATE DATABASE IF NOT EXISTS "bi_golden" ON CLUSTER 'test_cluster';

CREATE DATABASE IF NOT EXISTS "bi_golden_consumer" ON CLUSTER 'test_cluster';
-- global --
-- lifecycle --
-- bi-it.golden_sibling.pause-ingress --
-- bi-it.golden_sibling.pause-ingress --
-- bi-it.nullable.pause-ingress --
-- bi-it.nullable.pause-ingress --
-- lifecycle --
-- bi-it.golden_sibling.commandStorage --
CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_golden_sibling_command_store_local" ON CLUSTER 'test_cluster'
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
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/golden/test_cluster/tables/{shard}/{database}/{table}', '{replica}')
  PARTITION BY toYYYYMM("create_time")
  ORDER BY "id"
  COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"STORE","aggregate":"bi-it.golden_sibling"}';

CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_golden_sibling_command_store" ON CLUSTER 'test_cluster'
AS "bi_golden"."bi_it_golden_sibling_command_store_local"
ENGINE = Distributed('test_cluster', "bi_golden",
                     'bi_it_golden_sibling_command_store_local', sipHash64("aggregate_id"))
COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"STORE","aggregate":"bi-it.golden_sibling"}';
-- bi-it.golden_sibling.commandStorage --
-- bi-it.golden_sibling.stateStorage --
CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_golden_sibling_state_store_local" ON CLUSTER 'test_cluster'
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
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/golden/test_cluster/tables/{shard}/{database}/{table}', '{replica}', "version")
  PARTITION BY toYYYYMM("create_time")
  ORDER BY ("tenant_id", "aggregate_id", "version")
  COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"STORE","aggregate":"bi-it.golden_sibling"}';

CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_golden_sibling_state_store" ON CLUSTER 'test_cluster'
AS "bi_golden"."bi_it_golden_sibling_state_store_local"
ENGINE = Distributed('test_cluster', "bi_golden",
                     'bi_it_golden_sibling_state_store_local', sipHash64("tenant_id", "aggregate_id"))
COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"STORE","aggregate":"bi-it.golden_sibling"}';
-- bi-it.golden_sibling.stateStorage --
-- bi-it.golden_sibling.stateLast --
CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_golden_sibling_state_last_store_local" ON CLUSTER 'test_cluster'
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
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/golden/test_cluster/tables/{shard}/{database}/{table}', '{replica}', "version")
  PARTITION BY toYYYYMM("first_event_time")
  ORDER BY ("tenant_id", "aggregate_id")
  COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"STORE","aggregate":"bi-it.golden_sibling"}';

CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_golden_sibling_state_last_store" ON CLUSTER 'test_cluster'
AS "bi_golden"."bi_it_golden_sibling_state_last_store_local"
ENGINE = Distributed('test_cluster', "bi_golden",
                     'bi_it_golden_sibling_state_last_store_local', sipHash64("tenant_id", "aggregate_id"))
COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"STORE","aggregate":"bi-it.golden_sibling"}';

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_golden_consumer"."bi_it_golden_sibling_state_last_consumer" ON CLUSTER 'test_cluster'
TO "bi_golden"."bi_it_golden_sibling_state_last_store"
AS (
SELECT *
FROM "bi_golden"."bi_it_golden_sibling_state_store"
) COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"CONSUMER","aggregate":"bi-it.golden_sibling"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_golden_sibling_state_last" ON CLUSTER 'test_cluster'
AS (SELECT * FROM "bi_golden"."bi_it_golden_sibling_state_last_store" FINAL)
COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"VIEW","aggregate":"bi-it.golden_sibling"}';
-- bi-it.golden_sibling.stateLast --
-- bi-it.golden_sibling.expansion --
CREATE OR REPLACE VIEW "bi_golden"."bi_it_golden_sibling_state_last_root" ON CLUSTER 'test_cluster' AS (
SELECT
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtractArrayRaw("__source"."state", 'items') AS "items",
JSONExtract("__source"."state", 'name', 'String') AS "name",
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
FROM "bi_golden"."bi_it_golden_sibling_state_last" AS "__source"
) COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"VIEW","aggregate":"bi-it.golden_sibling"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_golden_sibling_state_last_root_items" ON CLUSTER 'test_cluster' AS (
WITH
arrayJoin(arrayZip(arrayEnumerate(JSONExtractArrayRaw("__source"."state", 'items')),
                   JSONExtractArrayRaw("__source"."state", 'items'))) AS "__cursor__items",
tupleElement("__cursor__items", 2) AS "items"
SELECT
JSONExtract("__source"."state", 'id', 'String') AS "id",
JSONExtract("items", 'quantity', 'Int32') AS "items__quantity",
JSONExtract("items", 'sku', 'String') AS "items__sku",
JSONExtract("__source"."state", 'name', 'String') AS "name",
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
FROM "bi_golden"."bi_it_golden_sibling_state_last" AS "__source"
) COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"VIEW","aggregate":"bi-it.golden_sibling"}';
-- bi-it.golden_sibling.expansion --
-- bi-it.golden_sibling.commandPublic --
CREATE OR REPLACE VIEW "bi_golden"."bi_it_golden_sibling_command" ON CLUSTER 'test_cluster'
AS (SELECT * FROM "bi_golden"."bi_it_golden_sibling_command_store" FINAL)
COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"VIEW","aggregate":"bi-it.golden_sibling"}';
-- bi-it.golden_sibling.commandPublic --
-- bi-it.golden_sibling.statePublic --
CREATE OR REPLACE VIEW "bi_golden"."bi_it_golden_sibling_state" ON CLUSTER 'test_cluster'
AS (SELECT * FROM "bi_golden"."bi_it_golden_sibling_state_store" FINAL)
COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"VIEW","aggregate":"bi-it.golden_sibling"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_golden_sibling_state_event" ON CLUSTER 'test_cluster'
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
FROM "bi_golden"."bi_it_golden_sibling_state"
) COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"VIEW","aggregate":"bi-it.golden_sibling"}';
-- bi-it.golden_sibling.statePublic --
-- bi-it.nullable.commandStorage --
-- bi-it.nullable.commandStorage --
-- bi-it.nullable.stateStorage --
-- bi-it.nullable.stateStorage --
-- bi-it.nullable.stateLast --
-- bi-it.nullable.stateLast --
-- bi-it.nullable.expansion --
-- bi-it.nullable.expansion --
-- bi-it.nullable.commandPublic --
-- bi-it.nullable.commandPublic --
-- bi-it.nullable.statePublic --
-- bi-it.nullable.statePublic --
-- bi-it.golden_sibling.commandIngress --
CREATE TABLE "bi_golden_consumer"."bi_it_golden_sibling_command_queue" ON CLUSTER 'test_cluster'
("data" String)
ENGINE = Kafka('localhost:9093', 'wow.bi-it.golden_sibling.command',
               'wow-bi.909af1c347eff0133c78fd708611cedb.bi_it_golden_sibling_command_consumer', 'JSONAsString')
COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"QUEUE","aggregate":"bi-it.golden_sibling"}';

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_golden_consumer"."bi_it_golden_sibling_command_consumer" ON CLUSTER 'test_cluster'
TO "bi_golden"."bi_it_golden_sibling_command_store"
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
       toDateTime64(JSONExtractInt("data", 'createTime') / 1000.0, 3, 'UTC') AS "create_time"
FROM "bi_golden_consumer"."bi_it_golden_sibling_command_queue"
) COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"CONSUMER","aggregate":"bi-it.golden_sibling"}';
-- bi-it.golden_sibling.commandIngress --
-- bi-it.golden_sibling.stateIngress --
CREATE TABLE "bi_golden_consumer"."bi_it_golden_sibling_state_queue" ON CLUSTER 'test_cluster'
(
    "data" String
) ENGINE = Kafka('localhost:9093', 'wow.bi-it.golden_sibling.state',
                 'wow-bi.909af1c347eff0133c78fd708611cedb.bi_it_golden_sibling_state_consumer', 'JSONAsString')
COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"QUEUE","aggregate":"bi-it.golden_sibling"}';

CREATE MATERIALIZED VIEW IF NOT EXISTS "bi_golden_consumer"."bi_it_golden_sibling_state_consumer" ON CLUSTER 'test_cluster'
TO "bi_golden"."bi_it_golden_sibling_state_store"
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
       toDateTime64(JSONExtractInt("data", 'firstEventTime') / 1000.0, 3, 'UTC') AS "first_event_time",
       toDateTime64(JSONExtractInt("data", 'createTime') / 1000.0, 3, 'UTC') AS "create_time",
       JSONExtract("data", 'tags', 'Map(String, Array(String))') AS "tags",
       JSONExtractBool("data", 'deleted') AS "deleted"
FROM "bi_golden_consumer"."bi_it_golden_sibling_state_queue"
) COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"CONSUMER","aggregate":"bi-it.golden_sibling"}';
-- bi-it.golden_sibling.stateIngress --
-- bi-it.nullable.commandIngress --
-- bi-it.nullable.commandIngress --
-- bi-it.nullable.stateIngress --
-- bi-it.nullable.stateIngress --
-- deployment-anchor --
CREATE OR REPLACE VIEW "bi_golden_consumer"."__wow_bi_deployment" ON CLUSTER 'test_cluster' AS (SELECT 1 AS "alive" WHERE 0) COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"ANCHOR","anchor":{"phase":"STABLE","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","consumerIdentity":"909af1c347eff0133c78fd708611cedb","durableInventory":[{"key":{"database":"bi_golden","name":"bi_it_golden_sibling_command_store"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_golden_sibling_command_store_local"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_golden_sibling_state_last_store"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_golden_sibling_state_last_store_local"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_golden_sibling_state_store"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_golden_sibling_state_store_local"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_command_store"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_command_store_local"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_state_last_store"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_state_last_store_local"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_state_store"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_state_store_local"},"status":"ACTIVE"},{"key":{"database":"bi_golden_consumer","name":"bi_it_golden_sibling_command_queue"},"status":"ACTIVE"},{"key":{"database":"bi_golden_consumer","name":"bi_it_golden_sibling_state_queue"},"status":"ACTIVE"},{"key":{"database":"bi_golden_consumer","name":"bi_it_nullable_command_queue"},"status":"ACTIVE"},{"key":{"database":"bi_golden_consumer","name":"bi_it_nullable_state_queue"},"status":"ACTIVE"}]}}';
-- deployment-anchor --
