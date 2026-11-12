-- Run on any node of the cluster; {cluster}, {shard}, {replica} and {database} are the macros ClickHouse defines for it.
-- The Sink inserts into and reads the Distributed tables, which carry the names it expects; a _local table is one shard's replicated copy.

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_events_local` ON CLUSTER '{cluster}'
(
    `id` String CODEC(ZSTD(1)),
    `tenant_id` String CODEC(ZSTD(1)),
    `aggregate_id` String,
    `version` UInt32 CODEC(Delta, ZSTD(1)),
    `command_id` String CODEC(ZSTD(1)),
    `request_id` String CODEC(ZSTD(1)),
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC') CODEC(Delta, ZSTD(1)),
    `owner_id` String CODEC(ZSTD(1)),
    `space_id` String CODEC(ZSTD(1)),
    `deleted` Bool,
    `access` String CODEC(ZSTD(1)),
    `correlation_id` String CODEC(ZSTD(1)),
    `causation_id` String CODEC(ZSTD(1)),
    `headers` Map(String, String) CODEC(ZSTD(1)),
    `operator_id` String CODEC(ZSTD(1)),
    `operator_tenant_id` String CODEC(ZSTD(1)),
    `operator_acted_elsewhere` Bool,
    `events` Array(Tuple(name LowCardinality(String), revision UInt32, payload String)) CODEC(ZSTD(1)),
    `state` String CODEC(ZSTD(1)),
    `state_fingerprint` String CODEC(ZSTD(1)),
    `state_numbers` Map(String, String) CODEC(ZSTD(1))
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}')
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version)
SETTINGS replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

-- _state has the columns of _state_events but the events and the headers, and computes the flat ones.
CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_local` ON CLUSTER '{cluster}'
(
    `id` String CODEC(ZSTD(1)),
    `tenant_id` String CODEC(ZSTD(1)),
    `aggregate_id` String,
    `version` UInt32 CODEC(Delta, ZSTD(1)),
    `command_id` String CODEC(ZSTD(1)),
    `request_id` String CODEC(ZSTD(1)),
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC') CODEC(Delta, ZSTD(1)),
    `owner_id` String CODEC(ZSTD(1)),
    `space_id` String CODEC(ZSTD(1)),
    `deleted` Bool,
    `access` String CODEC(ZSTD(1)),
    `correlation_id` String CODEC(ZSTD(1)),
    `causation_id` String CODEC(ZSTD(1)),
    `operator_id` String CODEC(ZSTD(1)),
    `operator_tenant_id` String CODEC(ZSTD(1)),
    `operator_acted_elsewhere` Bool,
    `state` String CODEC(ZSTD(1)),
    `state_fingerprint` String CODEC(ZSTD(1)),
    `state_numbers` Map(String, String) CODEC(ZSTD(1)),
    `access_owner` Nullable(String) MATERIALIZED JSONExtract(access, 'owner', 'Nullable(String)') COMMENT 'wow:cb2ca92a12a84185',
    `access_space` Nullable(String) MATERIALIZED JSONExtract(access, 'space', 'Nullable(String)') COMMENT 'wow:d04ee76ae254029d',
    `status` String MATERIALIZED JSONExtractString(state, 'status') COMMENT 'wow:7770d215250c3cc1',
    `note` Nullable(String) MATERIALIZED JSONExtract(state, 'note', 'Nullable(String)') COMMENT 'wow:8e528fd6dd433f30',
    `quantity` Int64 MATERIALIZED JSONExtract(state, 'quantity', 'Int64') COMMENT 'wow:de224aeb8bf7629c',
    `total` Decimal(38, 2) MATERIALIZED toDecimal128OrZero(state_numbers['total'], 2) COMMENT 'wow:336ba53b0b5c800e',
    `price` Decimal(38, 0) MATERIALIZED toDecimal128OrZero(state_numbers['price'], 0) COMMENT 'wow:f273feebcfeba26b',
    `discount` Nullable(Decimal(38, 18)) MATERIALIZED toDecimal128OrNull(state_numbers['discount'], 18) COMMENT 'wow:f41862a517046c64',
    `rate` Float64 MATERIALIZED JSONExtract(state, 'rate', 'Float64') COMMENT 'wow:7bd9491e8e41a742',
    `paid` Bool MATERIALIZED JSONExtract(state, 'paid', 'Bool') COMMENT 'wow:9239a7d46de01f6e',
    `state_version` Int64 MATERIALIZED JSONExtract(state, 'version', 'Int64') COMMENT 'wow:4e0c748d3474d842'
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/{database}/{table}', '{replica}', version)
ORDER BY (tenant_id, aggregate_id)
SETTINGS replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_events_local` ON CLUSTER '{cluster}'
(
    `id` String CODEC(ZSTD(1)),
    `tenant_id` String CODEC(ZSTD(1)),
    `aggregate_id` String,
    `version` UInt32 CODEC(Delta, ZSTD(1)),
    `sequence` UInt32,
    `event_name` LowCardinality(String),
    `event_revision` UInt32,
    `payload` String CODEC(ZSTD(1)),
    `command_id` String CODEC(ZSTD(1)),
    `request_id` String CODEC(ZSTD(1)),
    `command_type` LowCardinality(String),
    `commit_time` DateTime64(3, 'UTC') CODEC(Delta, ZSTD(1)),
    `owner_id` String CODEC(ZSTD(1)),
    `space_id` String CODEC(ZSTD(1)),
    `correlation_id` String CODEC(ZSTD(1)),
    `causation_id` String CODEC(ZSTD(1))
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
(SELECT id, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, owner_id, space_id, deleted, access, correlation_id, causation_id, operator_id, operator_tenant_id, operator_acted_elsewhere, state, state_fingerprint, state_numbers
FROM `default`.`shop_order_state_events_local`)
COMMENT 'wow-sink-format:1';

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_events_mv` ON CLUSTER '{cluster}' TO `default`.`shop_order_events_local` AS
(SELECT id, tenant_id, aggregate_id, version, toUInt32(sequence) AS sequence, event.name AS event_name, event.revision AS event_revision, event.payload AS payload, command_id, request_id, command_type, commit_time, owner_id, space_id, correlation_id, causation_id
FROM `default`.`shop_order_state_events_local`
ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence)
COMMENT 'wow-sink-format:1';

CREATE OR REPLACE VIEW `default`.`shop_order_state_flat` ON CLUSTER '{cluster}' AS
SELECT `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted`, `access`, `status`, `note`, `quantity`, `total`, `price`, `discount`, `rate`, `paid`, `state_version`
FROM `default`.`shop_order_state` FINAL
COMMENT 'wow-sink-format:1 wow-sink-flat:abec05d592597f99';
