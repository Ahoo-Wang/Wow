/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.clickhouse.sink

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.clickhouse.sink.SinkFixtures.ORDER
import me.ahoo.wow.manifest.AccessAttributeManifest
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days

class ClickHouseSinkSchemaTest {
    private val flat = listOf(FlatColumn("total", "Decimal(38, 2)", "toDecimal128OrZero(state_numbers['total'], 2)"))

    @Test
    fun `the base table should be a monthly ReplacingMergeTree by tenant, aggregate and version`() {
        val schema = ClickHouseSinkSchema(ORDER, "analytics", AggregateSinkOptions(historyTtl = 30.days), flat)
        schema.target.assert().isEqualTo("shop_order_state_events")
        schema.ddl.first().assert().isEqualTo(
            """
            CREATE TABLE IF NOT EXISTS `analytics`.`shop_order_state_events`
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
                `events` Array(Tuple(name LowCardinality(String), revision UInt32, payload String)) CODEC(ZSTD(3)),
                `state` String CODEC(ZSTD(3)),
                `state_fingerprint` String CODEC(ZSTD(1)),
                `state_numbers` Map(String, String) CODEC(ZSTD(1))
            )
            ENGINE = ReplacingMergeTree
            PARTITION BY toYYYYMM(commit_time)
            ORDER BY (tenant_id, aggregate_id, version)
            TTL toDateTime(commit_time) + toIntervalSecond(2592000)
            SETTINGS non_replicated_deduplication_window = 1000
            COMMENT 'wow-sink-format:1'
            """.trimIndent(),
        )
    }

    @Test
    fun `the views should fill the current state and the events from the base table`() {
        val ddl = ClickHouseSinkSchema(ORDER, "analytics", AggregateSinkOptions(historyTtl = 1.days), flat, 50).ddl
        ddl.size.assert().isEqualTo(5)
        // The current state keeps the base table's columns but the events and the headers, then computes the flat ones.
        val total = ComputedColumn("total", "Decimal(38, 2)", "toDecimal128OrZero(state_numbers['total'], 2)")
        ddl[1].assert().isEqualTo(
            "CREATE TABLE IF NOT EXISTS `analytics`.`shop_order_state`\n(\n" +
                ClickHouseSinkSchema.STATE_COLUMNS.joinToString(",\n") { "    ${ClickHouseSinkSchema.definitionOf(it)}" } +
                ",\n    `total` Decimal(38, 2) MATERIALIZED toDecimal128OrZero(state_numbers['total'], 2) " +
                "COMMENT 'wow:${total.fingerprint}'" +
                "\n)\nENGINE = ReplacingMergeTree(version)\nORDER BY (tenant_id, aggregate_id)\n" +
                "SETTINGS non_replicated_deduplication_window = 50\nCOMMENT 'wow-sink-format:1'",
        )
        ClickHouseSinkSchema.STATE_COLUMNS.assert().isEqualTo(
            ClickHouseSinkSchema.BASE_COLUMNS.filterNot { it.name == "events" || it.name == "headers" },
        )
        ddl[2].assert().contains("`event_name` LowCardinality(String)")
        ddl[2].assert().endsWith(
            "ENGINE = ReplacingMergeTree\nPARTITION BY toYYYYMM(commit_time)\nORDER BY (tenant_id, aggregate_id, version, sequence)\n" +
                "TTL toDateTime(commit_time) + toIntervalSecond(86400)\nSETTINGS non_replicated_deduplication_window = 50\n" +
                "COMMENT 'wow-sink-format:1'",
        )
        // A materialized view's SELECT is in parentheses, so that ClickHouse reads COMMENT as the view's.
        ddl[3].assert().isEqualTo(
            "CREATE MATERIALIZED VIEW IF NOT EXISTS `analytics`.`shop_order_state_mv` TO `analytics`.`shop_order_state` AS\n" +
                "(SELECT ${ClickHouseSinkSchema.STATE_COLUMNS.joinToString(", ") { it.name }}\n" +
                "FROM `analytics`.`shop_order_state_events`)\nCOMMENT 'wow-sink-format:1'",
        )
        ddl[4].assert().isEqualTo(
            "CREATE MATERIALIZED VIEW IF NOT EXISTS `analytics`.`shop_order_events_mv` TO `analytics`.`shop_order_events` AS\n" +
                "(SELECT id, tenant_id, aggregate_id, version, toUInt32(sequence) AS sequence, event.name AS event_name, " +
                "event.revision AS event_revision, event.payload AS payload, command_id, request_id, command_type, " +
                "commit_time, owner_id, space_id, correlation_id, causation_id\n" +
                "FROM `analytics`.`shop_order_state_events`\n" +
                "ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence)\nCOMMENT 'wow-sink-format:1'",
        )
        val schema = ClickHouseSinkSchema(ORDER, "analytics", AggregateSinkOptions(historyTtl = 1.days), flat, 50)
        schema.flatView(FlatLineage(listOf("0a", schema.flatFingerprint))).assert().isEqualTo(
            "CREATE OR REPLACE VIEW `analytics`.`shop_order_state_flat` AS\n" +
                "SELECT `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted`, " +
                "`access`, `total`\n" +
                "FROM `analytics`.`shop_order_state` FINAL\n" +
                "COMMENT 'wow-sink-format:1 wow-sink-flat:0a,${schema.flatFingerprint}'",
        )
        // The fingerprint is the definition's, whatever the database.
        schema.flatFingerprint.assert().hasSize(16)
            .isEqualTo(ClickHouseSinkSchema(ORDER, "default", AggregateSinkOptions(), flat).flatFingerprint)
            .isNotEqualTo(ClickHouseSinkSchema(ORDER, "default", AggregateSinkOptions(), emptyList()).flatFingerprint)
    }

    @Test
    fun `without the history only the current state and its flat view should exist`() {
        val schema = ClickHouseSinkSchema(ORDER, "default", AggregateSinkOptions(history = false), flat)
        schema.target.assert().isEqualTo("shop_order_state")
        schema.ddl.size.assert().isEqualTo(1)
        schema.ddl[0].assert().contains("ReplacingMergeTree(version)").doesNotContain("TTL")
        schema.expected.map { it.name }.assert().containsExactly("shop_order_state", "shop_order_state_flat")
    }

    @Test
    fun `validation should expect every table with its columns and the materialized views by name`() {
        val schema = ClickHouseSinkSchema(ORDER, "default", AggregateSinkOptions(), flat)
        schema.ddl.none { it.contains("TTL") }.assert().isTrue()
        schema.expected.map { it.name }.assert().containsExactly(
            "shop_order_state_events",
            "shop_order_state",
            "shop_order_events",
            "shop_order_state_mv",
            "shop_order_events_mv",
            "shop_order_state_flat",
        )
        schema.expected.filter { it.columns == null }.map { it.name }
            .assert().containsExactly("shop_order_state_mv", "shop_order_events_mv")
        schema.expected.last().columns!!.last().assert().isEqualTo(ClickHouseColumn("total", "Decimal(38, 2)"))
        schema.toString().assert().isEqualTo("ClickHouseSinkSchema(default.shop_order)")
    }

    @Test
    fun `every table and view should say the Sink's format in its comment`() {
        val schema = ClickHouseSinkSchema(ORDER, "default", AggregateSinkOptions(), flat)
        (schema.ddl + schema.flatView(FlatLineage.EMPTY.then(schema.flatFingerprint))).forEach {
            it.assert().contains("\nCOMMENT 'wow-sink-format:1")
        }
        ClickHouseSinkSchema.hasFormat("wow-sink-format:1").assert().isTrue()
        ClickHouseSinkSchema.hasFormat("wow-sink-format:1 wow-sink-flat:0a").assert().isTrue()
        ClickHouseSinkSchema.hasFormat("wow-sink-format:2").assert().isFalse()
        ClickHouseSinkSchema.hasFormat("wow-sink-format:10").assert().isFalse()
        ClickHouseSinkSchema.hasFormat("wow-sink-flat:0a").assert().isFalse()
        ClickHouseSinkSchema.hasFormat("").assert().isFalse()
        ClickHouseSinkSchema.hasFormat(null).assert().isFalse()
    }

    @Test
    fun `each declared access attribute should be a typed column computed from access`() {
        val access = AccessColumns.of(
            listOf(
                AccessAttributeManifest("region", AccessAttributeManifest.Type.STRING),
                AccessAttributeManifest("level", AccessAttributeManifest.Type.INTEGER),
                AccessAttributeManifest("vip", AccessAttributeManifest.Type.BOOLEAN),
                AccessAttributeManifest("teams", AccessAttributeManifest.Type.STRING, multiple = true),
            ),
        )
        // The built-in owner and space first, strings, then the declared ones.
        access.map { it.name to it.type }.assert().containsExactly(
            "access_owner" to "Nullable(String)",
            "access_space" to "Nullable(String)",
            "access_region" to "Nullable(String)",
            "access_level" to "Nullable(Int64)",
            "access_vip" to "Nullable(Bool)",
            "access_teams" to "Array(String)",
        )
        access.last().expression.assert().isEqualTo("JSONExtract(access, 'teams', 'Array(String)')")
        val schema = ClickHouseSinkSchema(ORDER, "default", AggregateSinkOptions(), flat, accessColumns = access)
        // The access columns come first, then the flat ones; each says its definition's fingerprint.
        schema.computed.map { it.name }.assert()
            .containsExactly(
                "access_owner",
                "access_space",
                "access_region",
                "access_level",
                "access_vip",
                "access_teams",
                "total",
            )
        val region = access.single { it.name == "access_region" }
        schema.ddl[1].assert().contains(
            "    `access_region` Nullable(String) MATERIALIZED JSONExtract(access, 'region', 'Nullable(String)') " +
                "COMMENT 'wow:${region.fingerprint}',\n",
        )
        region.fingerprint.assert().hasSize(16)
            .isNotEqualTo(ComputedColumn("access_region", "String", region.expression).fingerprint)
    }

    @Test
    fun `a missing computed column should be added after the one before it`() {
        val access = AccessColumns.of(listOf(AccessAttributeManifest("region", AccessAttributeManifest.Type.STRING)))
        val schema = ClickHouseSinkSchema(ORDER, "default", AggregateSinkOptions(), flat, accessColumns = access)
        val alter = "ALTER TABLE `default`.`shop_order_state` ADD COLUMN IF NOT EXISTS"
        schema.addColumns(schema.computed).assert().containsExactly(
            "$alter ${access[0].definition} AFTER `state_numbers`",
            "$alter ${access[1].definition} AFTER `access_owner`",
            "$alter ${access[2].definition} AFTER `access_space`",
            "$alter ${schema.computed.last().definition} AFTER `access_region`",
        )
    }

    @Test
    fun `the current state should be at least its columns, each computed one of its definition`() {
        val schema = ClickHouseSinkSchema(ORDER, "default", AggregateSinkOptions(), flat)
        val state = schema.expected.single { it.name == "shop_order_state" }
        val total = schema.computed.single()
        val stored = ClickHouseSinkSchema.STATE_COLUMNS
        val own = ClickHouseColumn("total", total.type, total.comment)
        state.mismatch(stored + own).assert().isNull()
        // Another writer's column is no reason.
        val others = ClickHouseColumn("w", "Int64", "wow:1")
        state.mismatch(stored + own + others).assert().isNull()
        state.missing(stored).assert().containsExactly(total)
        val changed = ClickHouseColumn("total", "Float64", "wow:0123456789abcdef")
        val problem = state.mismatch(stored + changed)
        problem.assert().contains("shop_order_state has total Float64 (wow:0123456789abcdef)")
        problem.assert().contains("which this version computes as Decimal(38, 2)")
        problem.assert().contains("cannot change a column's definition without rewriting every row")
        // A stored column of a computed one's name is another stored column.
        state.mismatch(stored + ClickHouseColumn("total", "Decimal(38, 2)")).assert()
            .contains("state_numbers Map(String, String), total Decimal(38, 2)], not")
        state.mismatch(stored.drop(1)).assert().startsWith("shop_order_state has the columns [tenant_id String,")
    }

    @Test
    fun `table names should be lower case with unsafe characters replaced`() {
        ClickHouseSinkSchema.prefixOf(AggregateType("Shop-EU", "order.Line")).assert().isEqualTo("shop_eu_order_line")
        ClickHouseSinkSchema(ORDER, "my`db", AggregateSinkOptions(), emptyList()).qualified("a`b")
            .assert().isEqualTo("`my\\`db`.`a\\`b`")
    }
}
