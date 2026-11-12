-- The codec check's table (bench.sh measure --program=clickhouse-insert): the Sink's `_state_events` of the
-- benchmark's account, with the codecs it creates, held to the Sink's DDL by ScaleCheckDdlTest. The harness also creates
-- it without its codecs (LZ4), and fills both from the same rows. Statements end with a semicolon at the end of a line.

CREATE TABLE IF NOT EXISTS `default`.`transfer_account_state_events`
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
