-- The access scale check's warehouse (bench.sh measure): 100,000 accounts in each of two tenants, the caller's `(0)`
-- and another, in the Sink's `_state` (its DDL without the history: its stored columns and its access columns, which
-- the access restriction reads; its flat columns, which no query here reads, left out), and 1,000,000 rows of a
-- dataset over them in each tenant, ten rows an account. An account's owner is one of 100 (`u0` to `u99`), and half
-- of the accounts have a second commit that moved them to another owner, in a part of its own that merges stay off,
-- so `FINAL` merges them on every query. Statements end with a semicolon at the end of a line.

CREATE TABLE IF NOT EXISTS `default`.`transfer_account_state`
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
    `state_numbers` Map(String, String),
    `access_owner` Nullable(String) MATERIALIZED JSONExtract(access, 'owner', 'Nullable(String)') COMMENT 'wow:cb2ca92a12a84185',
    `access_space` Nullable(String) MATERIALIZED JSONExtract(access, 'space', 'Nullable(String)') COMMENT 'wow:d04ee76ae254029d'
)
ENGINE = ReplacingMergeTree(version)
ORDER BY (tenant_id, aggregate_id)
SETTINGS non_replicated_deduplication_window = 1000
COMMENT 'wow-sink-format:1';

SYSTEM STOP MERGES `default`.`transfer_account_state`;

INSERT INTO `default`.`transfer_account_state` (id, tenant_id, aggregate_id, version, owner_id, access)
SELECT concat(tenant, '/a', toString(number), '@1'), tenant, concat('a', toString(number)), 1,
       concat('u', toString((number + 50) % 100)),
       concat('{"owner":"u', toString((number + 50) % 100), '","space":null}')
FROM numbers(100000) ARRAY JOIN ['(0)', 'other'] AS tenant;

INSERT INTO `default`.`transfer_account_state` (id, tenant_id, aggregate_id, version, owner_id, access)
SELECT concat(tenant, '/a', toString(number), '@2'), tenant, concat('a', toString(number)), 2,
       concat('u', toString(number % 100)),
       concat('{"owner":"u', toString(number % 100), '","space":null}')
FROM numbers(100000) ARRAY JOIN ['(0)', 'other'] AS tenant
WHERE number % 2 = 0;

CREATE TABLE IF NOT EXISTS `default`.`account_rows`
(
    `id` String,
    `tenant_id` String,
    `account_id` String,
    `branch` LowCardinality(String),
    `amount` Decimal(18, 2),
    `created_at` DateTime64(3, 'UTC')
)
ENGINE = MergeTree
ORDER BY (tenant_id, id);

INSERT INTO `default`.`account_rows`
SELECT concat('r', toString(number)), tenant, concat('a', toString(intDiv(number, 10))),
       concat('b', toString(number % 10)), toDecimal64(number % 1000, 2),
       fromUnixTimestamp64Milli(toInt64(1760000000000 + number * 1000), 'UTC')
FROM numbers(1000000) ARRAY JOIN ['(0)', 'other'] AS tenant;
