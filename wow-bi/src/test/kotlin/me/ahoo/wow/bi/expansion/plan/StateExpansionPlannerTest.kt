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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.bi.BiScriptDiagnosticCode
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ObjectMapStrategy
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.bi.expansion.BIAggregate
import me.ahoo.wow.bi.expansion.BIAggregateState
import me.ahoo.wow.bi.expansion.Item
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.junit.jupiter.api.Test

class StateExpansionPlannerTest {
    private val biAggregateMetadata = aggregateMetadata<BIAggregate, BIAggregateState>()

    @Test
    fun `should plan deterministic root and collection views structurally`() {
        val plan = StateExpansionPlanner().plan(biAggregateMetadata)

        plan.views.map { it.targetTableName }.assert().containsExactly(
            "bi_aggregate_state_last_root",
            "bi_aggregate_state_last_root_items",
            "bi_aggregate_state_last_root_like_list_item",
            "bi_aggregate_state_last_root_nested_list",
            "bi_aggregate_state_last_root_nested_list_list",
            "bi_aggregate_state_last_root_set",
        )
        val rootView = plan.views.first()
        rootView.columns.map { it.targetName }.assert().contains(
            "item__id",
            "nested__child__id",
        )
        rootView.columns.map { it.targetName }.assert()
            .isEqualTo(rootView.columns.map { it.targetName }.sorted())
        rootView.columns.first { it.targetName == "item" }.run {
            placement.assert().isEqualTo(ColumnPlacement.WITH)
            extraction.assert().isEqualTo(ColumnExtraction.JsonString("state", "item"))
        }
        rootView.columns.first { it.targetName == "item__id" }.run {
            placement.assert().isEqualTo(ColumnPlacement.SELECT)
            extraction.assert().isEqualTo(ColumnExtraction.JsonValue("item", "id"))
        }
        rootView.columns.first { it.targetName == "items" }.run {
            sqlType.assert().isEqualTo("Array(String)")
            placement.assert().isEqualTo(ColumnPlacement.SELECT)
            extraction.assert().isEqualTo(ColumnExtraction.JsonArray("state", "items"))
            inherited.assert().isFalse()
        }
        plan.diagnostics.single { it.path == "mapItem" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.OBJECT_MAP_FALLBACK)
            aggregate.assert().isEqualTo("bi-service.aggregate")
            message.contains(Item::class.java.name).assert().isTrue()
        }
    }

    @Test
    fun `should build child view after freezing all same-table sibling columns`() {
        val plan = StateExpansionPlanner().plan(siblingAggregateMetadata)

        val rootView = plan.views.first { it.targetTableName.endsWith("_root") }
        rootView.columns.map { it.targetName }.assert().contains(
            "alpha__owner_id",
            "zeta__value",
        )

        val childView = plan.views.single { it.targetTableName.endsWith("_alpha__children") }
        childView.columns.map { it.targetName }.assert().contains(
            "alpha__owner_id",
            "zeta__value",
            "alpha__children__id",
        )
    }

    @Test
    fun `should not count root in max expansion depth and keep truncated object as raw string`() {
        val plan = StateExpansionPlanner(BiScriptOptions(maxExpansionDepth = 1)).plan(biAggregateMetadata)
        val rootView = plan.views.first()

        rootView.columns.first { it.targetName == "nested__child" }.run {
            sqlType.assert().isEqualTo("String")
            placement.assert().isEqualTo(ColumnPlacement.SELECT)
            extraction.assert().isEqualTo(ColumnExtraction.JsonString("nested", "child"))
        }
        rootView.columns.none { it.targetName == "nested__child__id" }.assert().isTrue()
        plan.diagnostics.single { it.path == "nested.child" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.MAX_DEPTH_REACHED)
            aggregate.assert().isEqualTo("bi-service.aggregate")
        }
    }

    @Test
    fun `should keep truncated object array as raw string array`() {
        val plan = StateExpansionPlanner(BiScriptOptions(maxExpansionDepth = 1)).plan(siblingAggregateMetadata)
        val rootView = plan.views.first()

        rootView.columns.first { it.targetName == "alpha__children" }.run {
            sqlType.assert().isEqualTo("Array(String)")
            placement.assert().isEqualTo(ColumnPlacement.SELECT)
            extraction.assert().isEqualTo(ColumnExtraction.JsonArray("alpha", "children"))
        }
        plan.views.none { it.targetTableName.endsWith("_alpha__children") }.assert().isTrue()
        plan.diagnostics.single { it.path == "alpha.children" }.code.assert()
            .isEqualTo(BiScriptDiagnosticCode.MAX_DEPTH_REACHED)
    }

    @Test
    fun `should include aggregate path and actual type in strict object map failure`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner(
                BiScriptOptions(objectMapStrategy = ObjectMapStrategy.FAIL)
            ).plan(biAggregateMetadata)
        }.hasMessageContaining("bi-service.aggregate")
            .hasMessageContaining("likeMapItem")
            .hasMessageContaining(Item::class.java.name)
    }

    @Test
    fun `should include aggregate path and actual type in strict unsupported failure`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner().plan(unsupportedAggregateMetadata)
        }.hasMessageContaining("bi-service.unsupported")
            .hasMessageContaining("unsupported")
            .hasMessageContaining(Thread::class.java.name)
    }

    @Test
    fun `should downgrade unsupported platform objects structurally with diagnostics`() {
        val plan = StateExpansionPlanner(
            BiScriptOptions(unsupportedTypeStrategy = UnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC)
        ).plan(unsupportedAggregateMetadata)
        val rootView = plan.views.single()

        rootView.columns.first { it.targetName == "unsupported" }.run {
            sqlType.assert().isEqualTo("String")
            extraction.assert().isEqualTo(ColumnExtraction.JsonString("state", "unsupported"))
        }
        plan.diagnostics.single { it.path == "unsupported" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.UNSUPPORTED_TYPE_FALLBACK)
            aggregate.assert().isEqualTo("bi-service.unsupported")
            message.contains(Thread::class.java.name).assert().isTrue()
        }
    }
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class SiblingAggregate(private val state: SiblingState)

private class SiblingState(override val id: String) : Identifier {
    val alpha: CollectionOwner = CollectionOwner()
    val zeta: ScalarSibling = ScalarSibling()
}

private data class CollectionOwner(
    val children: List<CollectionChild> = emptyList(),
    val ownerId: String = "",
)

private data class CollectionChild(val id: String = "")

private data class ScalarSibling(val value: String = "")

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class UnsupportedAggregate(private val state: UnsupportedState)

private class UnsupportedState(override val id: String) : Identifier {
    val unsupported: Thread = Thread.currentThread()
}

private val siblingAggregateMetadata = aggregateMetadata<SiblingAggregate, SiblingState>()
private val unsupportedAggregateMetadata = aggregateMetadata<UnsupportedAggregate, UnsupportedState>()
