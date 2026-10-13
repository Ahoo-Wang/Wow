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
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days

class ClickHouseSinkSchemaTest {
    private val flat = listOf(FlatColumn("total", "Decimal(38, 2)", "toDecimal128OrZero(state_numbers['total'], 2)"))

    @Test
    fun `the base table should be a monthly MergeTree by tenant, aggregate and version`() {
        val schema = ClickHouseSinkSchema(ORDER, "analytics", AggregateSinkOptions(historyTtl = 30.days), flat)
        schema.target.assert().isEqualTo("shop_order_state_events")
        schema.ddl.first().assert().isEqualTo(
            """
            CREATE TABLE IF NOT EXISTS `analytics`.`shop_order_state_events`
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
                `tags` Map(String, Array(String)),
                `correlation_id` String,
                `causation_id` String,
                `headers` Map(String, String),
                `events` Array(Tuple(name LowCardinality(String), revision UInt32, payload String)),
                `state` String,
                `state_fingerprint` String,
                `state_numbers` Map(String, String)
            )
            ENGINE = MergeTree
            PARTITION BY toYYYYMM(commit_time)
            ORDER BY (tenant_id, aggregate_id, version)
            TTL toDateTime(commit_time) + toIntervalSecond(2592000)
            SETTINGS non_replicated_deduplication_window = 1000
            """.trimIndent(),
        )
    }

    @Test
    fun `the views should fill the current state and the events from the base table`() {
        val ddl = ClickHouseSinkSchema(ORDER, "analytics", AggregateSinkOptions(historyTtl = 1.days), flat, 50).ddl
        ddl.size.assert().isEqualTo(6)
        ddl[1].assert().startsWith("CREATE TABLE IF NOT EXISTS `analytics`.`shop_order_state`\n(\n    `id` String,")
        ddl[1].assert().endsWith(
            "ENGINE = ReplacingMergeTree(version)\nORDER BY (tenant_id, aggregate_id)\n" +
                "SETTINGS non_replicated_deduplication_window = 50",
        )
        ddl[2].assert().contains("`event_name` LowCardinality(String)")
        ddl[2].assert().endsWith(
            "ENGINE = MergeTree\nPARTITION BY toYYYYMM(commit_time)\nORDER BY (tenant_id, aggregate_id, version, sequence)\n" +
                "TTL toDateTime(commit_time) + toIntervalSecond(86400)\nSETTINGS non_replicated_deduplication_window = 50",
        )
        ddl[3].assert().isEqualTo(
            "CREATE MATERIALIZED VIEW IF NOT EXISTS `analytics`.`shop_order_state_mv` TO `analytics`.`shop_order_state` AS\n" +
                "SELECT ${ClickHouseSinkSchema.BASE_COLUMNS.joinToString(", ") { it.name }}\n" +
                "FROM `analytics`.`shop_order_state_events`",
        )
        ddl[4].assert().isEqualTo(
            "CREATE MATERIALIZED VIEW IF NOT EXISTS `analytics`.`shop_order_events_mv` TO `analytics`.`shop_order_events` AS\n" +
                "SELECT id, tenant_id, aggregate_id, version, toUInt32(sequence) AS sequence, event.name AS event_name, " +
                "event.revision AS event_revision, event.payload AS payload, command_id, request_id, command_type, " +
                "commit_time, owner_id, space_id, correlation_id, causation_id\n" +
                "FROM `analytics`.`shop_order_state_events`\n" +
                "ARRAY JOIN events AS event, arrayEnumerate(events) AS sequence",
        )
        ddl[5].assert().isEqualTo(
            "CREATE OR REPLACE VIEW `analytics`.`shop_order_state_flat` AS\n" +
                "SELECT `id`, `tenant_id`, `aggregate_id`, `version`, `commit_time`, `owner_id`, `space_id`, `deleted`, " +
                "`tags`, toDecimal128OrZero(state_numbers['total'], 2) AS `total`\n" +
                "FROM `analytics`.`shop_order_state` FINAL",
        )
    }

    @Test
    fun `without the history only the current state and its flat view should exist`() {
        val schema = ClickHouseSinkSchema(ORDER, "default", AggregateSinkOptions(history = false), flat)
        schema.target.assert().isEqualTo("shop_order_state")
        schema.ddl.size.assert().isEqualTo(2)
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
    fun `table names should be lower case with unsafe characters replaced`() {
        ClickHouseSinkSchema.prefixOf(AggregateType("Shop-EU", "order.Line")).assert().isEqualTo("shop_eu_order_line")
        ClickHouseSinkSchema(ORDER, "my`db", AggregateSinkOptions(), emptyList()).qualified("a`b")
            .assert().isEqualTo("`my\\`db`.`a\\`b`")
    }
}
