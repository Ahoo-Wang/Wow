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
import me.ahoo.wow.bi.type.ClickHouseType
import org.junit.jupiter.api.Test

class ClickHouseScriptRendererTest {
    @Test
    fun `should render complete expansion statements in plan order`() {
        val plan = StateExpansionPlan(
            views = listOf(
                ExpansionViewPlan(
                    targetTableName = "first_view",
                    sourceTableName = "source",
                    columns = emptyList(),
                ),
                ExpansionViewPlan(
                    targetTableName = "second_view",
                    sourceTableName = "source",
                    columns = emptyList(),
                ),
            ),
            diagnostics = emptyList(),
        )
        val renderer = ClickHouseScriptRenderer()

        val statements = renderer.renderExpansionStatements(plan)

        statements.assert().hasSize(2)
        statements[0].assert().contains("\"first_view\"")
        statements[1].assert().contains("\"second_view\"")
        statements.forEach { statement ->
            statement.trimEnd().endsWith(';').assert().isTrue()
            statement.windowed("CREATE VIEW".length)
                .count { it == "CREATE VIEW" }
                .assert()
                .isEqualTo(1)
        }
        renderer.renderExpansion(plan).assert().isEqualTo(statements.joinToString("\n"))
    }

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
                            type = ClickHouseType.String,
                            extraction = ColumnExtraction.JsonString("state", "nested'property"),
                            placement = ColumnPlacement.WITH,
                        ),
                        column(
                            name = "rawNested",
                            targetName = "raw_nested",
                            type = ClickHouseType.String,
                            extraction = ColumnExtraction.JsonRaw("state", "raw'nested"),
                            placement = ColumnPlacement.WITH,
                        ),
                        column(
                            name = "items",
                            targetName = "items",
                            type = ClickHouseType.String,
                            extraction = ColumnExtraction.ArrayJoin("state", "items"),
                            placement = ColumnPlacement.WITH,
                        ),
                        column(
                            name = "amount",
                            targetName = "amount",
                            type = ClickHouseType.Decimal(18, 2),
                            extraction = ColumnExtraction.JsonValue("state", "amount"),
                            placement = ColumnPlacement.SELECT,
                        ),
                        column(
                            name = "rawItems",
                            targetName = "raw_items",
                            type = ClickHouseType.Array(ClickHouseType.String),
                            extraction = ColumnExtraction.JsonArray("state", "rawItems"),
                            placement = ColumnPlacement.SELECT,
                        ),
                        column(
                            name = "sourceVersion",
                            targetName = "copied_version",
                            type = ClickHouseType.UInt32,
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
            "JSONExtract(\"state\", 'amount', 'Decimal(18,2)') AS \"amount\""
        )
        script.assert().contains(
            "JSONExtractArrayRaw(\"state\", 'rawItems') AS \"raw_items\""
        )
        script.assert().contains("\"version\" AS \"copied_version\"")
        script.assert().contains("FROM \"bi\\\"db\".\"source\\\\table\";")
    }

    @Test
    fun `should render nested nullable types from structural model`() {
        val view = ExpansionViewPlan(
            targetTableName = "target",
            sourceTableName = "source",
            columns = listOf(
                column(
                    name = "scalar",
                    targetName = "scalar",
                    type = ClickHouseType.Nullable(ClickHouseType.Int32),
                    extraction = ColumnExtraction.JsonValue("state", "scalar"),
                    placement = ColumnPlacement.SELECT,
                ),
                column(
                    name = "values",
                    targetName = "values",
                    type = ClickHouseType.Array(ClickHouseType.Nullable(ClickHouseType.Int32)),
                    extraction = ColumnExtraction.JsonValue("state", "values"),
                    placement = ColumnPlacement.SELECT,
                ),
                column(
                    name = "mapping",
                    targetName = "mapping",
                    type = ClickHouseType.Map(
                        ClickHouseType.String,
                        ClickHouseType.Nullable(ClickHouseType.Int32),
                    ),
                    extraction = ColumnExtraction.JsonValue("state", "mapping"),
                    placement = ColumnPlacement.SELECT,
                ),
            ),
        )

        val script = ClickHouseScriptRenderer().renderExpansion(
            StateExpansionPlan(views = listOf(view), diagnostics = emptyList())
        )

        script.assert().contains("JSONExtract(\"state\", 'scalar', 'Nullable(Int32)')")
        script.assert().contains("JSONExtract(\"state\", 'values', 'Array(Nullable(Int32))')")
        script.assert().contains(
            "JSONExtract(\"state\", 'mapping', 'Map(String, Nullable(Int32))')"
        )
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
        type: ClickHouseType,
        extraction: ColumnExtraction,
        placement: ColumnPlacement,
    ) = ColumnPlan(
        name = name,
        path = name,
        targetName = targetName,
        type = type,
        extraction = extraction,
        placement = placement,
    )
}
