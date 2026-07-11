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
import me.ahoo.wow.bi.expansion.plan.ColumnReference
import me.ahoo.wow.bi.expansion.plan.ExpansionViewPlan
import me.ahoo.wow.bi.expansion.plan.StateExpansionPlan
import me.ahoo.wow.bi.type.ClickHouseType
import me.ahoo.wow.configuration.MetadataSearcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ClickHouseScriptRendererTest {
    @Test
    fun `should render immutable individual statements for every DDL family`() {
        val aggregate = MetadataSearcher.localAggregates.single { it.aggregateName == "aggregate" }
        val renderer = ClickHouseScriptRenderer()
        val global = renderer.renderGlobalStatements()
        val clear = renderer.renderClearStatements(aggregate, listOf("root_view", "child_view"))
        val command = renderer.renderCommandStatements(aggregate)
        val stateEvent = renderer.renderStateEventStatements(aggregate)
        val stateLast = renderer.renderStateLastStatements(aggregate)

        global.assert().hasSize(2)
        clear.assert().hasSize(14)
        command.assert().hasSize(4)
        stateEvent.assert().hasSize(5)
        stateLast.assert().hasSize(3)
        listOf(global, clear, command, stateEvent, stateLast).flatten().forEach { statement ->
            statement.lineSequence().none { it.trimStart().startsWith("--") }.assert().isTrue()
            statement.trimEnd().endsWith(';').assert().isTrue()
        }
        renderer.renderGlobal().assert().isEqualTo(global.joinToString("\n\n"))
        renderer.renderClear(aggregate, listOf("root_view", "child_view"))
            .assert().isEqualTo(clear.joinToString("\n\n"))
        renderer.renderCommand(aggregate).assert().isEqualTo(command.joinToString("\n\n"))
        renderer.renderStateEvent(aggregate).assert().isEqualTo(stateEvent.joinToString("\n\n"))
        renderer.renderStateLast(aggregate).assert().isEqualTo(stateLast.joinToString("\n\n"))

        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (command as MutableList<String>).clear()
        }
    }

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
        renderer.renderExpansion(plan).assert().isEqualTo(statements.joinToString("\n\n"))
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
                            extraction = ColumnExtraction.JsonString(input("state"), "nested'property"),
                            placement = ColumnPlacement.WITH,
                        ),
                        column(
                            name = "rawNested",
                            targetName = "raw_nested",
                            type = ClickHouseType.String,
                            extraction = ColumnExtraction.JsonRaw(input("state"), "raw'nested"),
                            placement = ColumnPlacement.WITH,
                        ),
                        column(
                            name = "items",
                            targetName = "items",
                            type = ClickHouseType.String,
                            extraction = ColumnExtraction.ArrayJoin(input("state"), "items"),
                            placement = ColumnPlacement.WITH,
                        ),
                        column(
                            name = "amount",
                            targetName = "amount",
                            type = ClickHouseType.Decimal(18, 2),
                            extraction = ColumnExtraction.JsonValue(input("state"), "amount"),
                            placement = ColumnPlacement.SELECT,
                        ),
                        column(
                            name = "rawItems",
                            targetName = "raw_items",
                            type = ClickHouseType.Array(ClickHouseType.String),
                            extraction = ColumnExtraction.JsonArray(input("state"), "rawItems"),
                            placement = ColumnPlacement.SELECT,
                        ),
                        column(
                            name = "sourceVersion",
                            targetName = "copied_version",
                            type = ClickHouseType.UInt32,
                            extraction = ColumnExtraction.Reference(input("version")),
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
            "JSONExtractString(\"__source\".\"state\", 'nested''property') AS " +
                "\"nested\\\"alias\""
        )
        script.assert().contains(
            "simpleJSONExtractRaw(\"__source\".\"state\", 'raw''nested') AS \"raw_nested\""
        )
        script.assert().contains(
            "arrayJoin(JSONExtractArrayRaw(\"__source\".\"state\", 'items')) AS \"items\""
        )
        script.assert().contains(
            "JSONExtract(\"__source\".\"state\", 'amount', 'Decimal(18,2)') AS \"amount\""
        )
        script.assert().contains(
            "JSONExtractArrayRaw(\"__source\".\"state\", 'rawItems') AS \"raw_items\""
        )
        script.assert().contains("\"__source\".\"version\" AS \"copied_version\"")
        script.assert().contains(
            "FROM \"bi\\\"db\".\"source\\\\table\" AS \"__source\";"
        )
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
                    extraction = ColumnExtraction.JsonValue(input("state"), "scalar"),
                    placement = ColumnPlacement.SELECT,
                ),
                column(
                    name = "values",
                    targetName = "values",
                    type = ClickHouseType.Array(ClickHouseType.Nullable(ClickHouseType.Int32)),
                    extraction = ColumnExtraction.JsonValue(input("state"), "values"),
                    placement = ColumnPlacement.SELECT,
                ),
                column(
                    name = "mapping",
                    targetName = "mapping",
                    type = ClickHouseType.Map(
                        ClickHouseType.String,
                        ClickHouseType.Nullable(ClickHouseType.Int32),
                    ),
                    extraction = ColumnExtraction.JsonValue(input("state"), "mapping"),
                    placement = ColumnPlacement.SELECT,
                ),
            ),
        )

        val script = ClickHouseScriptRenderer().renderExpansion(
            StateExpansionPlan(views = listOf(view), diagnostics = emptyList())
        )

        script.assert().contains(
            "JSONExtract(\"__source\".\"state\", 'scalar', 'Nullable(Int32)')"
        )
        script.assert().contains(
            "JSONExtract(\"__source\".\"state\", 'values', 'Array(Nullable(Int32))')"
        )
        script.assert().contains(
            "JSONExtract(\"__source\".\"state\", 'mapping', " +
                "'Map(String, Nullable(Int32))')"
        )
    }

    @Test
    fun `should qualify physical input columns to prevent output alias shadowing`() {
        val view = ExpansionViewPlan(
            targetTableName = "target",
            sourceTableName = "source",
            columns = listOf(
                column(
                    name = "id",
                    targetName = "id",
                    type = ClickHouseType.String,
                    extraction = ColumnExtraction.JsonValue(input("state"), "id"),
                    placement = ColumnPlacement.SELECT,
                )
            ),
        )

        val script = ClickHouseScriptRenderer().renderExpansion(
            StateExpansionPlan(views = listOf(view), diagnostics = emptyList())
        )

        script.assert().contains(
            "JSONExtract(\"__source\".\"state\", 'id', 'String') AS \"id\""
        )
        script.assert().contains("\"__source\".\"id\" AS \"__id\"")
        script.assert().contains("FROM \"bi_db\".\"source\" AS \"__source\";")
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
            "\"__source\".\"id\" AS \"__id\"",
            "\"__source\".\"aggregate_id\" AS \"__aggregate_id\"",
            "\"__source\".\"tenant_id\" AS \"__tenant_id\"",
            "\"__source\".\"owner_id\" AS \"__owner_id\"",
            "\"__source\".\"space_id\" AS \"__space_id\"",
            "\"__source\".\"command_id\" AS \"__command_id\"",
            "\"__source\".\"request_id\" AS \"__request_id\"",
            "\"__source\".\"version\" AS \"__version\"",
            "\"__source\".\"first_operator\" AS \"__first_operator\"",
            "\"__source\".\"first_event_time\" AS \"__first_event_time\"",
            "\"__source\".\"create_time\" AS \"__create_time\"",
            "\"__source\".\"tags\" AS \"__tags\"",
            "\"__source\".\"deleted\" AS \"__deleted\"",
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

    private fun input(name: String): ColumnReference = ColumnReference.Input(name)
}
