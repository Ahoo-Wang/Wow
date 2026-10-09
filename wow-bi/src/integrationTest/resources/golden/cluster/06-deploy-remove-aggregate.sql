-- operation: Deploy, destructive: false
-- diagnostic: CLUSTER_INTERNAL_REPLICATION_REQUIRED EXTERNAL_CONFIGURATION_REQUIRED * topology.cluster
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
CREATE DATABASE IF NOT EXISTS "bi_golden" ON CLUSTER 'test_cluster';

CREATE DATABASE IF NOT EXISTS "bi_golden_consumer" ON CLUSTER 'test_cluster';
-- global --
-- lifecycle --
-- bi-it.nullable.pause-ingress --
-- bi-it.nullable.pause-ingress --
-- reconcile-observed-catalog --
DROP VIEW IF EXISTS "bi_golden"."bi_it_golden_sibling_state_last_root_items" ON CLUSTER 'test_cluster' SYNC;

DROP VIEW IF EXISTS "bi_golden_consumer"."bi_it_golden_sibling_state_last_consumer" ON CLUSTER 'test_cluster' SYNC;

DROP VIEW IF EXISTS "bi_golden_consumer"."bi_it_golden_sibling_command_consumer" ON CLUSTER 'test_cluster' SYNC;

DROP VIEW IF EXISTS "bi_golden"."bi_it_golden_sibling_state_last_root" ON CLUSTER 'test_cluster' SYNC;

DROP VIEW IF EXISTS "bi_golden_consumer"."bi_it_golden_sibling_state_consumer" ON CLUSTER 'test_cluster' SYNC;

DROP TABLE IF EXISTS "bi_golden_consumer"."bi_it_golden_sibling_command_queue" ON CLUSTER 'test_cluster' SYNC;

DROP VIEW IF EXISTS "bi_golden"."bi_it_golden_sibling_state_event" ON CLUSTER 'test_cluster' SYNC;

DROP TABLE IF EXISTS "bi_golden_consumer"."bi_it_golden_sibling_state_queue" ON CLUSTER 'test_cluster' SYNC;

DROP VIEW IF EXISTS "bi_golden"."bi_it_golden_sibling_state_last" ON CLUSTER 'test_cluster' SYNC;

DROP VIEW IF EXISTS "bi_golden"."bi_it_golden_sibling_command" ON CLUSTER 'test_cluster' SYNC;

DROP VIEW IF EXISTS "bi_golden"."bi_it_golden_sibling_state" ON CLUSTER 'test_cluster' SYNC;
-- reconcile-observed-catalog --
-- lifecycle --
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
-- bi-it.nullable.commandIngress --
-- bi-it.nullable.commandIngress --
-- bi-it.nullable.stateIngress --
-- bi-it.nullable.stateIngress --
-- deployment-anchor --
CREATE OR REPLACE VIEW "bi_golden_consumer"."__wow_bi_deployment" ON CLUSTER 'test_cluster' AS (SELECT 1 AS "alive" WHERE 0) COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"ANCHOR","anchor":{"phase":"STABLE","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","consumerIdentity":"909af1c347eff0133c78fd708611cedb","durableInventory":[{"key":{"database":"bi_golden","name":"bi_it_golden_sibling_command_store"},"status":"RETIRED"},{"key":{"database":"bi_golden","name":"bi_it_golden_sibling_command_store_local"},"status":"RETIRED"},{"key":{"database":"bi_golden","name":"bi_it_golden_sibling_state_last_store"},"status":"RETIRED"},{"key":{"database":"bi_golden","name":"bi_it_golden_sibling_state_last_store_local"},"status":"RETIRED"},{"key":{"database":"bi_golden","name":"bi_it_golden_sibling_state_store"},"status":"RETIRED"},{"key":{"database":"bi_golden","name":"bi_it_golden_sibling_state_store_local"},"status":"RETIRED"},{"key":{"database":"bi_golden","name":"bi_it_nullable_command_store"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_command_store_local"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_state_last_store"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_state_last_store_local"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_state_store"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_state_store_local"},"status":"ACTIVE"},{"key":{"database":"bi_golden_consumer","name":"bi_it_nullable_command_queue"},"status":"ACTIVE"},{"key":{"database":"bi_golden_consumer","name":"bi_it_nullable_state_queue"},"status":"ACTIVE"}]}}';
-- deployment-anchor --
