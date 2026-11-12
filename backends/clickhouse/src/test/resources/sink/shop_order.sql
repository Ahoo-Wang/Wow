CREATE TABLE IF NOT EXISTS `default`.`wow_catalog`
(
    `format` UInt32,
    `database` String,
    `table` String,
    `table_uuid` UUID,
    `role` LowCardinality(String),
    `service` String,
    `context` String,
    `aggregate` String,
    `ownership` LowCardinality(String),
    `tenant_column` String,
    `owner_column` String,
    `space_column` String,
    `sensitive` Array(Tuple(path String, level String, mask String, event String, revision UInt32)),
    `references` Array(Tuple(path String, context String, aggregate String)),
    `event_revisions` Map(String, UInt32),
    `fingerprint` String,
    `columns_hash` String,
    `access` LowCardinality(String),
    `written_at` DateTime64(3, 'UTC') DEFAULT now64(3),
    `writer` String MATERIALIZED currentUser()
)
ENGINE = ReplacingMergeTree(written_at)
ORDER BY (`database`, `table`, fingerprint);

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state_events`
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
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version)
SETTINGS non_replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state`
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
ENGINE = ReplacingMergeTree(version)
ORDER BY (tenant_id, aggregate_id)
SETTINGS non_replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE TABLE IF NOT EXISTS `default`.`shop_order_events`
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
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version, sequence)
SETTINGS non_replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_state_mv` TO `default`.`shop_order_state` AS
(SELECT id, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, owner_id, space_id, deleted, access, correlation_id, causation_id, operator_id, operator_tenant_id, operator_acted_elsewhere, state, state_fingerprint, state_numbers
FROM `default`.`shop_order_state_events`)
COMMENT 'wow-sink-format:1';

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_events_mv` TO `default`.`shop_order_events` AS
(SELECT id, tenant_id, aggregate_id, version, toUInt32(sequence) AS sequence, event.name AS event_name, event.revision AS event_revision, event.payload AS payload, command_id, request_id, command_type, commit_time, owner_id, space_id, correlation_id, causation_id
FROM `default`.`shop_order_state_events`
ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence)
COMMENT 'wow-sink-format:1';

CREATE OR REPLACE VIEW `default`.`shop_order_state_flat` AS
SELECT `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted`, `access`, `status`, `note`, `quantity`, `total`, `price`, `discount`, `rate`, `paid`, `state_version`
FROM `default`.`shop_order_state` FINAL
COMMENT 'wow-sink-format:1 wow-sink-flat:abec05d592597f99';
