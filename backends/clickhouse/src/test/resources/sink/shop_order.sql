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
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version)
SETTINGS non_replicated_deduplication_window = 1000;

CREATE TABLE IF NOT EXISTS `default`.`shop_order_state`
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
ENGINE = ReplacingMergeTree(version)
ORDER BY (tenant_id, aggregate_id)
SETTINGS non_replicated_deduplication_window = 1000;

CREATE TABLE IF NOT EXISTS `default`.`shop_order_events`
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
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(commit_time)
ORDER BY (tenant_id, aggregate_id, version, sequence)
SETTINGS non_replicated_deduplication_window = 1000;

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_state_mv` TO `default`.`shop_order_state` AS
SELECT id, tenant_id, aggregate_id, version, command_id, request_id, command_type, commit_time, owner_id, space_id, deleted, access, correlation_id, causation_id, headers, operator_id, operator_tenant_id, operator_cross_scope, events, state, state_fingerprint, state_numbers
FROM `default`.`shop_order_state_events`;

CREATE MATERIALIZED VIEW IF NOT EXISTS `default`.`shop_order_events_mv` TO `default`.`shop_order_events` AS
SELECT id, tenant_id, aggregate_id, version, toUInt32(sequence) AS sequence, event.name AS event_name, event.revision AS event_revision, event.payload AS payload, command_id, request_id, command_type, commit_time, owner_id, space_id, correlation_id, causation_id
FROM `default`.`shop_order_state_events`
ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence;

CREATE OR REPLACE VIEW `default`.`shop_order_state_flat` AS
SELECT `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted`, `access`, JSONExtractString(state, 'status') AS `status`, JSONExtract(state, 'note', 'Nullable(String)') AS `note`, JSONExtract(state, 'quantity', 'Int64') AS `quantity`, toDecimal128OrZero(state_numbers['total'], 2) AS `total`, toDecimal128OrZero(state_numbers['price'], 0) AS `price`, toDecimal128OrNull(state_numbers['discount'], 18) AS `discount`, JSONExtract(state, 'rate', 'Float64') AS `rate`, JSONExtract(state, 'paid', 'Bool') AS `paid`, JSONExtract(state, 'version', 'Int64') AS `state_version`
FROM `default`.`shop_order_state` FINAL
COMMENT 'wow-sink-flat:eb326856599fc0e7';
