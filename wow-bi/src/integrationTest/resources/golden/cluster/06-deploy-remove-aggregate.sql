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
-- ownership-registry --
CREATE TABLE IF NOT EXISTS "bi_golden_consumer"."__wow_bi_registry_623dfbee6748cde26fc115e6da93e2e5" ON CLUSTER 'test_cluster'
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
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/golden/test_cluster/control/wow-bi/623dfbee6748cde26fc115e6da93e2e5', '{shard}-{replica}', "revision")
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
            ('623dfbee6748cde26fc115e6da93e2e5', 'HEAD', '', '', 'ANCHOR', NULL, NULL, '8a03081cbd24b8ebff79dd8eedef908c', 91, 'ACTIVE', '8a03081cbd24b8ebff79dd8eedef908c'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_command', 'VIEW', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'cb8b8da0470af202e65c083b5b149dfc', 75, 'PENDING_DROP', '1dd79c77bdb2fc7bd77583bde6ad2475'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_command_store', 'STORE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '0ae2a2bf74c21a10229025f4911f6576', 76, 'RETIRED', '80495e03f576599b6bef78510a7cf050'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_command_store_local', 'STORE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '75260063bb6a0609d9fb9f97839c7aa3', 77, 'RETIRED', 'cb020232acc700f9294fa15a357950df'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state', 'VIEW', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'edaac4ee36943c04f2aefba6404bf8d2', 78, 'PENDING_DROP', '6f074d6130c5aa3cadcfcb9d83d7ece7'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_event', 'VIEW', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'ac5a7dfad34d2904c249899c2b97b114', 79, 'PENDING_DROP', '45cbb9eeee42d90f75adfa37d4e8df6a'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last', 'VIEW', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '026c6e3b8efd1a46fce46463d4a63c44', 80, 'PENDING_DROP', 'c55b3f48ece7da6b17a9b0974c47d6e5'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_root', 'VIEW', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'c70daeafdde9d3b943b043f6d9faf4f3', 81, 'PENDING_DROP', '24331f20fd7a925d0fe491236eeaaf76'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_root_items', 'VIEW', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'aacd88598ee643a5a8cbb442224723c3', 82, 'PENDING_DROP', '2e0e2a1326fac1a8a0d691a750415b1c'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_store', 'STORE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'c9ee914a46233542a9c3d1e5ac34e33b', 83, 'RETIRED', 'c26b7773a8c4a28200195286acfe3baa'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_store_local', 'STORE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'f2727d7dd711635c6608cbb28cd01e46', 84, 'RETIRED', '400319700677ebb890a930253d3aa1ab'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_store', 'STORE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '312243b324244285d0c4795685a4bf70', 85, 'RETIRED', '753cb232fd2c24d0e7aeb49e0129b474'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_store_local', 'STORE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '02aff3afbade566ec9c3d10af4869934', 86, 'RETIRED', 'add9ca4f71fa2dbcb94057fdb534e6be'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', 'aeaaf818bcec879f2b413ec9614c99d7', 21, 'ACTIVE', '8ef4de7ce67c4afae2f754f576baf534'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command_store', 'STORE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '04db948710fdc5dcdb49ae9bc189399e', 22, 'ACTIVE', 'ddc8e3334e8237ea0e26ab16bfdc56d4'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command_store_local', 'STORE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '9caebedc683684c3050919152072d4b2', 23, 'ACTIVE', '92cac0339b83e9eacc759148dfb39f09'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '93eea4c3dc6af2e929af09f6f04b107f', 24, 'ACTIVE', 'eb5087894365098e3a1f05f017ac7ded'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_event', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '4479975a15bca892ebe4e3f79fc0094b', 25, 'ACTIVE', '12fda2afc0374788eebfdba91108dc7a'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '442e00039e3b093bf5cdcfd0d91faf35', 26, 'ACTIVE', '31a2615e7ca0cbec22e2048dccf59076'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', 'c85735b9b05ffd65aa71ab7233c2562c', 27, 'ACTIVE', 'dad08f23977576c2d598007b1eef23c8'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_field_7dc34f9338e4d4c34ea6d6664feeaad1', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '4650def40781fa890c1d8c7d556a67fb', 28, 'ACTIVE', '007b8143d136b15d4e79837b604f32b5'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_nullable_objects', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', 'f068d5dc2be4dddc981ec8f5dc169c1b', 29, 'ACTIVE', '0f69afc674d7d2ec861cba3498e6efcf'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_recovery_items', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '19440df1a7a7118d81f37afad9fb201b', 30, 'ACTIVE', '233ebf298311450d278dc042c20437fe'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_recovery_items_children', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '1f21a820fc88f490be62a440b436f3b4', 31, 'ACTIVE', '69b5b3486c690ee7217c059639370d5c'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_store', 'STORE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '190089b6d7446f9cf1cb6b7df38f14ec', 32, 'ACTIVE', 'c1059d68b9561697797306b3e19dea30'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_store_local', 'STORE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '636607b73aca21d2c1882645633abb94', 33, 'ACTIVE', '8ea1ebc303ea68cecdaa003fa8688662'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_store', 'STORE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '2a9e337b3e7c45b6a0f2a59a064ecd98', 34, 'ACTIVE', '193e65844bc71ec6ff9902fd9f2843e0'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_store_local', 'STORE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', 'd99b07967252f509f047c90ae3af4515', 35, 'ACTIVE', '993a9694e96d497406964d4919e070d0'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_command_consumer', 'CONSUMER', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '17d1402bcd016eccb8cc6f90c9b1695f', 87, 'PENDING_DROP', '84f238bde32b105aa99197f313627cb7'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_command_queue', 'QUEUE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '46afd4b19d4d591e22ee839675621335', 88, 'PENDING_DROP', '9b0e75754e10aab1578affd2ef62bd2e'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_state_consumer', 'CONSUMER', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '4d024af58832675b8ff04734a41897b5', 89, 'PENDING_DROP', '4780e0954f36ce0833d620b86f7ab4fe'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_state_last_consumer', 'CONSUMER', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'fcb0ee490c402ad16aa685c50317c9b5', 90, 'PENDING_DROP', '025bca01813bf3052eb1ae54a01e0804'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_state_queue', 'QUEUE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'd3df8f8e65ec5c15e7c59379db4110fc', 91, 'PENDING_DROP', 'c7d064392b449a205b134cf98d0262e8'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_command_consumer', 'CONSUMER', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', 'c0efe90ecd1c4c744ebfd30dd294afcb', 36, 'ACTIVE', '1340d04b58c078502c73d894886a8f19'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_command_queue', 'QUEUE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '8fed9ad29d345b026bd4de035dad1490', 37, 'ACTIVE', 'df1e7a039938c3e1b5326860c70aa8d3'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_consumer', 'CONSUMER', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', 'f6204886a8f2618c855db05e7229324d', 38, 'ACTIVE', '8705a849abd0c3382cb5d5b43577b7d9'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_last_consumer', 'CONSUMER', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '0a9f87a4f0cabc21a85eaa1b6caa4cb9', 39, 'ACTIVE', '5d170b25c78f62239eb78a4c1c53e3f6'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_queue', 'QUEUE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '778ad3a17ebd5fddeccda98237030ccb', 40, 'ACTIVE', '6ac6a2aded14eeed09de43237a28920e');
-- ownership-registry-intent --
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
CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_nullable_command_store_local" ON CLUSTER 'test_cluster'
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
  COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"STORE","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';

CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_nullable_command_store" ON CLUSTER 'test_cluster'
AS "bi_golden"."bi_it_nullable_command_store_local"
ENGINE = Distributed('test_cluster', "bi_golden",
                     'bi_it_nullable_command_store_local', sipHash64("aggregate_id"))
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"STORE","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';
-- bi-it.nullable.commandStorage --
-- bi-it.nullable.stateStorage --
CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_nullable_state_store_local" ON CLUSTER 'test_cluster'
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
      COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"STORE","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';

CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_nullable_state_store" ON CLUSTER 'test_cluster'
AS "bi_golden"."bi_it_nullable_state_store_local"
ENGINE = Distributed('test_cluster', "bi_golden",
                     'bi_it_nullable_state_store_local', sipHash64("tenant_id", "aggregate_id"))
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"STORE","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';
-- bi-it.nullable.stateStorage --
-- bi-it.nullable.stateLast --
CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_nullable_state_last_store_local" ON CLUSTER 'test_cluster'
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
      COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"STORE","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';

CREATE TABLE IF NOT EXISTS "bi_golden"."bi_it_nullable_state_last_store" ON CLUSTER 'test_cluster'
AS "bi_golden"."bi_it_nullable_state_last_store_local"
ENGINE = Distributed('test_cluster', "bi_golden",
                     'bi_it_nullable_state_last_store_local', sipHash64("tenant_id", "aggregate_id"))
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"STORE","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last" ON CLUSTER 'test_cluster'
AS (SELECT * FROM "bi_golden"."bi_it_nullable_state_last_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';
-- bi-it.nullable.stateLast --
-- bi-it.nullable.expansion --
CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last_root" ON CLUSTER 'test_cluster' AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last_root_field_7dc34f9338e4d4c34ea6d6664feeaad1" ON CLUSTER 'test_cluster' AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last_root_nullable_objects" ON CLUSTER 'test_cluster' AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last_root_recovery_items" ON CLUSTER 'test_cluster' AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_last_root_recovery_items_children" ON CLUSTER 'test_cluster' AS (
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';
-- bi-it.nullable.expansion --
-- bi-it.nullable.commandPublic --
CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_command" ON CLUSTER 'test_cluster'
AS (SELECT * FROM "bi_golden"."bi_it_nullable_command_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';
-- bi-it.nullable.commandPublic --
-- bi-it.nullable.statePublic --
CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state" ON CLUSTER 'test_cluster'
AS (SELECT * FROM "bi_golden"."bi_it_nullable_state_store" FINAL)
COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';

CREATE OR REPLACE VIEW "bi_golden"."bi_it_nullable_state_event" ON CLUSTER 'test_cluster'
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
) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","aggregate":"bi-it.nullable","kind":"VIEW","consumerIdentity":"909af1c347eff0133c78fd708611cedb"}';
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
            ('623dfbee6748cde26fc115e6da93e2e5', 'HEAD', '', '', 'ANCHOR', NULL, NULL, 'b29f3639497caa7c1892161331f09909', 102, 'ACTIVE', 'b29f3639497caa7c1892161331f09909'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_command', 'VIEW', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'cb8b8da0470af202e65c083b5b149dfc', 92, 'TOMBSTONE', '78e6c6c40738c0f0ec32c0bba4cc2080'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_command_store', 'STORE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '0ae2a2bf74c21a10229025f4911f6576', 76, 'RETIRED', '80495e03f576599b6bef78510a7cf050'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_command_store_local', 'STORE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '75260063bb6a0609d9fb9f97839c7aa3', 77, 'RETIRED', 'cb020232acc700f9294fa15a357950df'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state', 'VIEW', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'edaac4ee36943c04f2aefba6404bf8d2', 93, 'TOMBSTONE', 'efec650cbe875fd5884d642c63693c2f'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_event', 'VIEW', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'ac5a7dfad34d2904c249899c2b97b114', 94, 'TOMBSTONE', 'e42e3173021b782714ccdc2cb28ffe1e'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last', 'VIEW', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '026c6e3b8efd1a46fce46463d4a63c44', 95, 'TOMBSTONE', 'f5d1ac79e8548060561d401afb987583'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_root', 'VIEW', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'c70daeafdde9d3b943b043f6d9faf4f3', 96, 'TOMBSTONE', 'ddc8101ce2c530604a8e02a8848de6af'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_root_items', 'VIEW', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'aacd88598ee643a5a8cbb442224723c3', 97, 'TOMBSTONE', 'cc1d3c54118406c7a77eff6144eae39c'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_store', 'STORE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'c9ee914a46233542a9c3d1e5ac34e33b', 83, 'RETIRED', 'c26b7773a8c4a28200195286acfe3baa'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_last_store_local', 'STORE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'f2727d7dd711635c6608cbb28cd01e46', 84, 'RETIRED', '400319700677ebb890a930253d3aa1ab'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_store', 'STORE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '312243b324244285d0c4795685a4bf70', 85, 'RETIRED', '753cb232fd2c24d0e7aeb49e0129b474'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_golden_sibling_state_store_local', 'STORE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '02aff3afbade566ec9c3d10af4869934', 86, 'RETIRED', 'add9ca4f71fa2dbcb94057fdb534e6be'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', 'aeaaf818bcec879f2b413ec9614c99d7', 21, 'ACTIVE', '8ef4de7ce67c4afae2f754f576baf534'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command_store', 'STORE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '04db948710fdc5dcdb49ae9bc189399e', 22, 'ACTIVE', 'ddc8e3334e8237ea0e26ab16bfdc56d4'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_command_store_local', 'STORE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '9caebedc683684c3050919152072d4b2', 23, 'ACTIVE', '92cac0339b83e9eacc759148dfb39f09'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '93eea4c3dc6af2e929af09f6f04b107f', 24, 'ACTIVE', 'eb5087894365098e3a1f05f017ac7ded'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_event', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '4479975a15bca892ebe4e3f79fc0094b', 25, 'ACTIVE', '12fda2afc0374788eebfdba91108dc7a'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '442e00039e3b093bf5cdcfd0d91faf35', 26, 'ACTIVE', '31a2615e7ca0cbec22e2048dccf59076'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', 'c85735b9b05ffd65aa71ab7233c2562c', 27, 'ACTIVE', 'dad08f23977576c2d598007b1eef23c8'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_field_7dc34f9338e4d4c34ea6d6664feeaad1', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '4650def40781fa890c1d8c7d556a67fb', 28, 'ACTIVE', '007b8143d136b15d4e79837b604f32b5'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_nullable_objects', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', 'f068d5dc2be4dddc981ec8f5dc169c1b', 29, 'ACTIVE', '0f69afc674d7d2ec861cba3498e6efcf'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_recovery_items', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '19440df1a7a7118d81f37afad9fb201b', 30, 'ACTIVE', '233ebf298311450d278dc042c20437fe'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_root_recovery_items_children', 'VIEW', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '1f21a820fc88f490be62a440b436f3b4', 31, 'ACTIVE', '69b5b3486c690ee7217c059639370d5c'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_store', 'STORE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '190089b6d7446f9cf1cb6b7df38f14ec', 32, 'ACTIVE', 'c1059d68b9561697797306b3e19dea30'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_last_store_local', 'STORE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '636607b73aca21d2c1882645633abb94', 33, 'ACTIVE', '8ea1ebc303ea68cecdaa003fa8688662'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_store', 'STORE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '2a9e337b3e7c45b6a0f2a59a064ecd98', 34, 'ACTIVE', '193e65844bc71ec6ff9902fd9f2843e0'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden', 'bi_it_nullable_state_store_local', 'STORE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', 'd99b07967252f509f047c90ae3af4515', 35, 'ACTIVE', '993a9694e96d497406964d4919e070d0'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_command_consumer', 'CONSUMER', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '17d1402bcd016eccb8cc6f90c9b1695f', 98, 'TOMBSTONE', '26e62063c13e5a347dd3c383ae51e0ee'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_command_queue', 'QUEUE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '46afd4b19d4d591e22ee839675621335', 99, 'TOMBSTONE', '1781e661648c0bf49a7caad854f0eabe'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_state_consumer', 'CONSUMER', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', '4d024af58832675b8ff04734a41897b5', 100, 'TOMBSTONE', '4f9c03ca026c4132436d8d307bb0d691'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_state_last_consumer', 'CONSUMER', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'fcb0ee490c402ad16aa685c50317c9b5', 101, 'TOMBSTONE', '5395610cbc2c84936c247896749301ae'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_golden_sibling_state_queue', 'QUEUE', 'bi-it.golden_sibling', '909af1c347eff0133c78fd708611cedb', 'd3df8f8e65ec5c15e7c59379db4110fc', 102, 'TOMBSTONE', 'e389500b9f633df1b6255ca4e545b4d1'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_command_consumer', 'CONSUMER', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', 'c0efe90ecd1c4c744ebfd30dd294afcb', 36, 'ACTIVE', '1340d04b58c078502c73d894886a8f19'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_command_queue', 'QUEUE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '8fed9ad29d345b026bd4de035dad1490', 37, 'ACTIVE', 'df1e7a039938c3e1b5326860c70aa8d3'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_consumer', 'CONSUMER', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', 'f6204886a8f2618c855db05e7229324d', 38, 'ACTIVE', '8705a849abd0c3382cb5d5b43577b7d9'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_last_consumer', 'CONSUMER', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '0a9f87a4f0cabc21a85eaa1b6caa4cb9', 39, 'ACTIVE', '5d170b25c78f62239eb78a4c1c53e3f6'),
('623dfbee6748cde26fc115e6da93e2e5', 'OBJECT', 'bi_golden_consumer', 'bi_it_nullable_state_queue', 'QUEUE', 'bi-it.nullable', '909af1c347eff0133c78fd708611cedb', '778ad3a17ebd5fddeccda98237030ccb', 40, 'ACTIVE', '6ac6a2aded14eeed09de43237a28920e');
-- ownership-registry-confirmation --
-- deployment-anchor --
CREATE OR REPLACE VIEW "bi_golden_consumer"."__wow_bi_deployment" ON CLUSTER 'test_cluster' AS (SELECT 1 AS "alive" WHERE 0) COMMENT 'wow-bi:{"protocolVersion":3,"layoutVersion":7,"phase":"STABLE","deploymentId":"623dfbee6748cde26fc115e6da93e2e5","configurationFingerprint":"909af1c347eff0133c78fd708611cedb","topologyFingerprint":"e81fceb63140d2e9cb73135b26daf352","kind":"ANCHOR","consumerIdentity":"909af1c347eff0133c78fd708611cedb","registryRevision":102}';
-- deployment-anchor --
