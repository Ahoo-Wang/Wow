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

package me.ahoo.wow.bi.renderer

import me.ahoo.test.asserts.assert
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.expansion.plan.ColumnExtraction
import me.ahoo.wow.bi.expansion.plan.ColumnPlacement
import me.ahoo.wow.bi.expansion.plan.ColumnPlan
import me.ahoo.wow.bi.expansion.plan.ExpansionViewPlan
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlan
import org.junit.jupiter.api.Test

class ClickHouseScriptRendererTest {
    @Test
    @Suppress("LongMethod")
    fun `should render every structural column extraction with centralized quoting`() {
        val plan = StateExpansionPlan(
            views = listOf(
                ExpansionViewPlan(
                    targetTableName = "target\"table",
                    sourceTableName = "source\\table",
                    columns = listOf(
                        column(
                            name = "nested",
                            targetName = "nested\"alias",
                            sqlType = "String",
                            extraction = ColumnExtraction.JsonString("state", "nested'property"),
                            placement = ColumnPlacement.WITH,
                        ),
                        column(
                            name = "rawNested",
                            targetName = "raw_nested",
                            sqlType = "String",
                            extraction = ColumnExtraction.JsonRaw("state", "raw'nested"),
                            placement = ColumnPlacement.WITH,
                        ),
                        column(
                            name = "items",
                            targetName = "items",
                            sqlType = "String",
                            extraction = ColumnExtraction.ArrayJoin("state", "items"),
                            placement = ColumnPlacement.WITH,
                        ),
                        column(
                            name = "amount",
                            targetName = "amount",
                            sqlType = "Decimal(18, 2)",
                            extraction = ColumnExtraction.JsonValue("state", "amount"),
                            placement = ColumnPlacement.SELECT,
                        ),
                        column(
                            name = "rawItems",
                            targetName = "raw_items",
                            sqlType = "Array(String)",
                            extraction = ColumnExtraction.JsonArray("state", "rawItems"),
                            placement = ColumnPlacement.SELECT,
                        ),
                        column(
                            name = "sourceVersion",
                            targetName = "copied_version",
                            sqlType = "UInt32",
                            extraction = ColumnExtraction.Source("version"),
                            placement = ColumnPlacement.SELECT,
                        ),
                    ),
                )
            ),
            diagnostics = emptyList(),
        )

        val script = ClickHouseScriptRenderer(
            BiScriptOptions(database = "bi\"db", cluster = "cluster'name")
        ).renderExpansion(plan)

        script.assert().contains(
            "CREATE VIEW IF NOT EXISTS \"bi\\\"db\".\"target\\\"table\" " +
                "ON CLUSTER 'cluster''name' AS"
        )
        script.assert().contains(
            "JSONExtractString(\"state\", 'nested''property') AS \"nested\\\"alias\""
        )
        script.assert().contains(
            "JSONExtractRaw(\"state\", 'raw''nested') AS \"raw_nested\""
        )
        script.assert().contains(
            "arrayJoin(JSONExtractArrayRaw(\"state\", 'items')) AS \"items\""
        )
        script.assert().contains(
            "JSONExtract(\"state\", 'amount', 'Decimal(18, 2)') AS \"amount\""
        )
        script.assert().contains(
            "JSONExtractArrayRaw(\"state\", 'rawItems') AS \"raw_items\""
        )
        script.assert().contains("\"version\" AS \"copied_version\"")
        script.assert().contains("FROM \"bi\\\"db\".\"source\\\\table\";")
    }

    @Test
    fun `should append the complete typed metadata projection once per view`() {
        val emptyView = ExpansionViewPlan(
            targetTableName = "target",
            sourceTableName = "source",
            columns = emptyList(),
        )

        val script = ClickHouseScriptRenderer().renderExpansion(
            StateExpansionPlan(views = listOf(emptyView), diagnostics = emptyList())
        )

        listOf(
            "\"id\" AS \"__id\"",
            "\"aggregate_id\" AS \"__aggregate_id\"",
            "\"tenant_id\" AS \"__tenant_id\"",
            "\"owner_id\" AS \"__owner_id\"",
            "\"space_id\" AS \"__space_id\"",
            "\"command_id\" AS \"__command_id\"",
            "\"request_id\" AS \"__request_id\"",
            "\"version\" AS \"__version\"",
            "\"first_operator\" AS \"__first_operator\"",
            "\"first_event_time\" AS \"__first_event_time\"",
            "\"create_time\" AS \"__create_time\"",
            "\"tags\" AS \"__tags\"",
            "\"deleted\" AS \"__deleted\"",
        ).forEach { metadataProjection ->
            script.windowed(metadataProjection.length)
                .count { it == metadataProjection }
                .assert()
                .isEqualTo(1)
        }
    }

    private fun column(
        name: String,
        targetName: String,
        sqlType: String,
        extraction: ColumnExtraction,
        placement: ColumnPlacement,
    ) = ColumnPlan(
        name = name,
        path = name,
        targetName = targetName,
        sqlType = sqlType,
        extraction = extraction,
        placement = placement,
    )
}
