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

package me.ahoo.wow.bi.expansion.plan

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.bi.type.ClickHouseType
import me.ahoo.wow.example.transfer.domain.Account
import me.ahoo.wow.example.transfer.domain.AccountState
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.junit.jupiter.api.Test

class StateExpansionPlannerNullableTest {
    @Test
    fun `should preserve scalar collection map and nested generic nullability structurally`() {
        val root = StateExpansionPlanner().plan(nullableAggregateMetadata).views.first()

        root.column("name").run {
            type.assert().isEqualTo(ClickHouseType.Nullable(ClickHouseType.String))
            extraction.assert().isEqualTo(ColumnExtraction.JsonValue(input("state"), "name"))
        }
        root.rawColumn("name").extraction.assert()
            .isEqualTo(ColumnExtraction.JsonRaw(input("state"), "name"))

        root.column("nullable_elements").run {
            type.assert().isEqualTo(
                ClickHouseType.Array(ClickHouseType.Nullable(ClickHouseType.String))
            )
        }
        root.columns.none { it.targetName == "__raw__nullable_elements" }.assert().isTrue()

        root.column("nullable_values").run {
            type.assert().isEqualTo(ClickHouseType.Array(ClickHouseType.String))
        }
        root.rawColumn("nullable_values").extraction.assert()
            .isEqualTo(ColumnExtraction.JsonRaw(input("state"), "nullableValues"))

        root.column("nullable_map_values").run {
            type.assert().isEqualTo(
                ClickHouseType.Map(
                    ClickHouseType.String,
                    ClickHouseType.Nullable(ClickHouseType.Int32),
                )
            )
        }
        root.columns.none { it.targetName == "__raw__nullable_map_values" }.assert().isTrue()

        root.column("nullable_map").run {
            type.assert().isEqualTo(
                ClickHouseType.Map(ClickHouseType.String, ClickHouseType.Int32)
            )
        }
        root.rawColumn("nullable_map").extraction.assert()
            .isEqualTo(ColumnExtraction.JsonRaw(input("state"), "nullableMap"))

        root.column("boxed__value").run {
            type.assert().isEqualTo(ClickHouseType.Nullable(ClickHouseType.String))
        }
        root.rawColumn("boxed__value").extraction.assert()
            .isEqualTo(ColumnExtraction.JsonRaw(alias("boxed"), "value"))
    }

    @Test
    fun `should make descendant nullable without duplicating its raw companion`() {
        val root = StateExpansionPlanner().plan(nullableAggregateMetadata).views.first()

        root.rawColumn("child").extraction.assert()
            .isEqualTo(ColumnExtraction.JsonRaw(input("state"), "child"))
        root.column("child__name").type.assert()
            .isEqualTo(ClickHouseType.Nullable(ClickHouseType.String))
        root.columns.none { it.targetName == "__raw__child__name" }.assert().isTrue()
    }

    @Test
    fun `should preserve nullable object element with child view raw source`() {
        val plan = StateExpansionPlanner().plan(nullableAggregateMetadata)
        val root = plan.views.first()
        val itemView = plan.views.single { it.targetTableName.endsWith("_items") }

        root.column("items").run {
            type.assert().isEqualTo(ClickHouseType.Array(ClickHouseType.String))
            extraction.assert().isEqualTo(ColumnExtraction.JsonArray(input("state"), "items"))
        }
        itemView.rawColumn("items").run {
            placement.assert().isEqualTo(ColumnPlacement.SELECT)
            extraction.assert().isEqualTo(ColumnExtraction.Reference(alias("items")))
        }
        itemView.column("items__name").type.assert()
            .isEqualTo(ClickHouseType.Nullable(ClickHouseType.String))
        itemView.columns.none { it.targetName == "__raw__items__name" }.assert().isTrue()
    }

    @Test
    fun `should map unknown Java reference as nullable while retaining annotated non-null id`() {
        val root = StateExpansionPlanner()
            .plan(aggregateMetadata<Account, AccountState>())
            .views
            .first()

        root.column("id").type.assert().isEqualTo(ClickHouseType.String)
        root.columns.none { it.targetName == "__raw__id" }.assert().isTrue()
        root.column("name").type.assert()
            .isEqualTo(ClickHouseType.Nullable(ClickHouseType.String))
        root.rawColumn("name").extraction.assert()
            .isEqualTo(ColumnExtraction.JsonRaw(input("state"), "name"))
    }

    private fun ExpansionViewPlan.column(targetName: String): ColumnPlan =
        columns.single { it.targetName == targetName }

    private fun ExpansionViewPlan.rawColumn(targetName: String): ColumnPlan =
        column("__raw__$targetName")

    private fun input(name: String): ColumnReference = ColumnReference.Input(name)

    private fun alias(name: String): ColumnReference = ColumnReference.Alias(name)
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class TruncatedPlatformObjectAggregate(private val state: TruncatedPlatformObjectState)

private class TruncatedPlatformObjectState(override val id: String) : Identifier {
    val name: String? = null
    val nullableElements: List<String?> = emptyList()
    val nullableValues: List<String>? = null
    val nullableMapValues: Map<String, Int?> = emptyMap()
    val nullableMap: Map<String, Int>? = null
    val child: NullableChild? = null
    val items: List<NullableChild?> = emptyList()
    val boxed: NullableBox<String?> = NullableBox(null)
}

private data class NullableChild(val name: String)

private data class NullableBox<T>(val value: T)

private val nullableAggregateMetadata =
    aggregateMetadata<TruncatedPlatformObjectAggregate, TruncatedPlatformObjectState>()
