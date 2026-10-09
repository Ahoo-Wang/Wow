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
-- lifecycle --
-- bi-it.nullable.pause-ingress --
-- bi-it.nullable.pause-ingress --
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
CREATE OR REPLACE VIEW "bi_golden_consumer"."__wow_bi_deployment" AS (SELECT 1 AS "alive" WHERE 0) COMMENT 'wow-bi:{"layoutVersion":8,"deploymentId":"623dfbee6748cde26fc115e6da93e2e5","kind":"ANCHOR","anchor":{"phase":"STABLE","configurationFingerprint":"04e164f980daedb379f00846a78ae7d1","topologyFingerprint":"4d8cb427323ced3b5bf32b79b1f5e58a","consumerIdentity":"0000000000000000000000000000ffff","durableInventory":[{"key":{"database":"bi_golden","name":"bi_it_nullable_command_store"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_state_last_store"},"status":"ACTIVE"},{"key":{"database":"bi_golden","name":"bi_it_nullable_state_store"},"status":"ACTIVE"},{"key":{"database":"bi_golden_consumer","name":"bi_it_nullable_command_queue"},"status":"ACTIVE"},{"key":{"database":"bi_golden_consumer","name":"bi_it_nullable_state_queue"},"status":"ACTIVE"}]}}';
-- deployment-anchor --
