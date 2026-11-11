-- Run on any node of the cluster; {cluster}, {shard}, {replica} and {database} are the macros ClickHouse defines for it.
-- The Sink inserts into and reads the Distributed tables, which carry the names it expects; a _local table is one shard's replicated copy.

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_events_local` ON CLUSTER '{cluster}'
(
    `id` String,
    `tenant_id` String,
    `aggregate_id` String,
    `version` UInt32,
    `command_id` String,
    `request_id` String,
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC'),
    `owner_id` String,
    `space_id` String,
    `deleted` Bool,
    `access` String,
    `correlation_id` String,
    `causation_id` String,
    `headers` Map(String, String),
    `operator_id` String,
    `operator_tenant_id` String,
    `operator_cross_scope` Bool,
    `events` Array(Tuple(name LowCardinality(String), revision UInt32, payload String)),
    `state` String,
    `state_fingerprint` String,
    `state_numbers` Map(String, String)
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}')
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version)
SETTINGS replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

-- _state has the columns of _state_events but the events and the headers.
CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_local` ON CLUSTER '{cluster}'
(
    `id` String,
    `tenant_id` String,
    `aggregate_id` String,
    `version` UInt32,
    `command_id` String,
    `request_id` String,
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC'),
    `owner_id` String,
    `space_id` String,
    `deleted` Bool,
    `access` String,
    `correlation_id` String,
    `causation_id` String,
    `operator_id` String,
    `operator_tenant_id` String,
    `operator_cross_scope` Bool,
    `state` String,
    `state_fingerprint` String,
    `state_numbers` Map(String, String)
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}', version)
ORDER BY (tenant_id, aggregate_id)
SETTINGS replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_events_local` ON CLUSTER '{cluster}'
(
    `id` String,
    `tenant_id` String,
    `aggregate_id` String,
    `version` UInt32,
    `sequence` UInt32,
    `event_name` LowCardinality(String),
    `event_revision` UInt32,
    `payload` String,
    `command_id` String,
    `request_id` String,
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC'),
    `owner_id` String,
    `space_id` String,
    `correlation_id` String,
    `causation_id` String
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}')
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version, sequence)
SETTINGS replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

-- Every commit of an aggregate goes to one shard: the sharding key is its tenant and id. The Sink reads the format from
-- the comment of the tables it expects, these Distributed ones, which `AS` does not copy.
CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_events` ON CLUSTER '{cluster}'
AS `default`.`shop_order_state_events_local`
ENGINE = Distributed('{cluster}', `default`, `shop_order_state_events_local`, sipHash64(tenant_id, aggregate_id))
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state` ON CLUSTER '{cluster}'
AS `default`.`shop_order_state_local`
ENGINE = Distributed('{cluster}', `default`, `shop_order_state_local`, sipHash64(tenant_id, aggregate_id))
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_events` ON CLUSTER '{cluster}'
AS `default`.`shop_order_events_local`
ENGINE = Distributed('{cluster}', `default`, `shop_order_events_local`, sipHash64(tenant_id, aggregate_id))
COMMENT 'wow-sink-format:1';

-- The views run on every node, from its own shard's local tables.
CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_state_mv` ON CLUSTER '{cluster}' TO `default`.`shop_order_state_local` AS
(SELECT id, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, owner_id, space_id, deleted, access, correlation_id, causation_id, operator_id, operator_tenant_id, operator_cross_scope, state, state_fingerprint, state_numbers
FROM `default`.`shop_order_state_events_local`)
COMMENT 'wow-sink-format:1';

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_events_mv` ON CLUSTER '{cluster}' TO `default`.`shop_order_events_local` AS
(SELECT id, tenant_id, aggregate_id, version, toUInt32(sequence) AS sequence, event.name AS event_name, event.revision AS event_revision, event.payload AS payload, command_id, request_id, command_type, commit_time, owner_id, space_id, correlation_id, causation_id
FROM `default`.`shop_order_state_events_local`
ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence)
COMMENT 'wow-sink-format:1';

CREATE OR REPLACE VIEW `default`.`shop_order_state_flat` ON CLUSTER '{cluster}' AS
SELECT `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted`, `access`, JSONExtractString(state, 'status') AS `status`, JSONExtract(state, 'note', 'Nullable(String)') AS `note`, JSONExtract(state, 'quantity', 'Int64') AS `quantity`, toDecimal128OrZero(state_numbers['total'], 2) AS `total`, toDecimal128OrZero(state_numbers['price'], 0) AS `price`, toDecimal128OrNull(state_numbers['discount'], 18) AS `discount`, JSONExtract(state, 'rate', 'Float64') AS `rate`, JSONExtract(state, 'paid', 'Bool') AS `paid`, JSONExtract(state, 'version', 'Int64') AS `state_version`
FROM `default`.`shop_order_state` FINAL
COMMENT 'wow-sink-format:1 wow-sink-flat:eb326856599fc0e7';
