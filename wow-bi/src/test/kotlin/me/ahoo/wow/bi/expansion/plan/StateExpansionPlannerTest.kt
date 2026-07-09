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
import org.junit.jupiter.api.assertAll

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
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw("state", "item"))
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
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw("nested", "child"))
        }
        rootView.columns.first { it.targetName == "nested__id" }.run {
            sqlType.assert().isEqualTo("String")
            extraction.assert().isEqualTo(ColumnExtraction.JsonValue("nested", "id"))
        }
        rootView.columns.none { it.targetName == "nested__child__id" }.assert().isTrue()
        plan.diagnostics.single { it.path == "nested.child" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.MAX_DEPTH_REACHED)
            aggregate.assert().isEqualTo("bi-service.aggregate")
        }
        plan.diagnostics.none { it.path == "nested.id" }.assert().isTrue()
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
    fun `should prioritize depth truncation over strict unsupported nested object`() {
        val plan = StateExpansionPlanner(BiScriptOptions(maxExpansionDepth = 1))
            .plan(truncatedPlatformObjectAggregateMetadata)

        plan.views.single().columns.first { it.targetName == "nested__value" }.run {
            sqlType.assert().isEqualTo("String")
            placement.assert().isEqualTo(ColumnPlacement.SELECT)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw("nested", "value"))
        }
        plan.diagnostics.single().run {
            path.assert().isEqualTo("nested.value")
            code.assert().isEqualTo(BiScriptDiagnosticCode.MAX_DEPTH_REACHED)
        }
        plan.diagnostics.none {
            it.code == BiScriptDiagnosticCode.UNSUPPORTED_TYPE_FALLBACK
        }.assert().isTrue()
    }

    @Test
    fun `should prioritize depth truncation over nested object map fallback`() {
        val plan = StateExpansionPlanner(BiScriptOptions(maxExpansionDepth = 1))
            .plan(truncatedObjectMapAggregateMetadata)

        plan.views.single().columns.first { it.targetName == "nested__values" }.run {
            sqlType.assert().isEqualTo("String")
            placement.assert().isEqualTo(ColumnPlacement.SELECT)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw("nested", "values"))
        }
        plan.diagnostics.single().run {
            path.assert().isEqualTo("nested.values")
            code.assert().isEqualTo(BiScriptDiagnosticCode.MAX_DEPTH_REACHED)
        }
        plan.diagnostics.none {
            it.code == BiScriptDiagnosticCode.OBJECT_MAP_FALLBACK
        }.assert().isTrue()
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
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw("state", "unsupported"))
        }
        plan.diagnostics.single { it.path == "unsupported" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.UNSUPPORTED_TYPE_FALLBACK)
            aggregate.assert().isEqualTo("bi-service.unsupported")
            message.contains(Thread::class.java.name).assert().isTrue()
        }
    }

    @Test
    fun `should reject non-string map key with complete java type`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner().plan(nonStringMapAggregateMetadata)
        }.hasMessageContaining("bi-service.non-string-map")
            .hasMessageContaining("values")
            .hasMessageContaining("java.util.Map<java.lang.Integer,java.lang.String>")
    }

    @Test
    fun `should preserve non-string map as raw json string with diagnostic`() {
        val plan = StateExpansionPlanner(
            BiScriptOptions(unsupportedTypeStrategy = UnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC)
        ).plan(nonStringMapAggregateMetadata)

        plan.views.single().columns.single { it.targetName == "values" }.run {
            sqlType.assert().isEqualTo("String")
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw("state", "values"))
        }
        plan.diagnostics.single().run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.UNSUPPORTED_TYPE_FALLBACK)
            aggregate.assert().isEqualTo("bi-service.non-string-map")
            path.assert().isEqualTo("values")
            message.contains("java.util.Map<java.lang.Integer,java.lang.String>").assert().isTrue()
        }
    }

    @Test
    fun `should reject raw map key with complete java type`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner().plan(rawMapAggregateMetadata)
        }.hasMessageContaining("bi-service.raw-map")
            .hasMessageContaining("values")
            .hasMessageContaining("java.util.Map<java.lang.Object,java.lang.Object>")
    }

    @Test
    fun `should preserve raw map as raw json string with diagnostic`() {
        val plan = StateExpansionPlanner(
            BiScriptOptions(unsupportedTypeStrategy = UnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC)
        ).plan(rawMapAggregateMetadata)

        plan.views.single().columns.single { it.targetName == "values" }.run {
            sqlType.assert().isEqualTo("String")
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw("state", "values"))
        }
        plan.diagnostics.single().run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.UNSUPPORTED_TYPE_FALLBACK)
            message.contains("java.util.Map<java.lang.Object,java.lang.Object>").assert().isTrue()
        }
    }

    @Test
    fun `should include complete generic value type in object map diagnostic`() {
        val plan = StateExpansionPlanner().plan(genericObjectMapAggregateMetadata)

        plan.diagnostics.single().run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.OBJECT_MAP_FALLBACK)
            message.contains("java.util.List<me.ahoo.wow.bi.expansion.Item>").assert().isTrue()
        }
    }

    @Test
    fun `should include complete generic value type in strict object map failure`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner(
                BiScriptOptions(objectMapStrategy = ObjectMapStrategy.FAIL)
            ).plan(genericObjectMapAggregateMetadata)
        }.hasMessageContaining("bi-service.generic-object-map")
            .hasMessageContaining("values")
            .hasMessageContaining("java.util.List<me.ahoo.wow.bi.expansion.Item>")
    }

    @Test
    fun `should reject platform collection element before building child view`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner().plan(platformCollectionAggregateMetadata)
        }.hasMessageContaining("bi-service.platform-collection")
            .hasMessageContaining("values")
            .hasMessageContaining("java.util.List<java.lang.Thread>")
    }

    @Test
    fun `should preserve platform collection as raw string array with diagnostic`() {
        val plan = StateExpansionPlanner(
            BiScriptOptions(unsupportedTypeStrategy = UnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC)
        ).plan(platformCollectionAggregateMetadata)

        plan.views.assert().hasSize(1)
        plan.views.single().columns.single { it.targetName == "values" }.run {
            sqlType.assert().isEqualTo("Array(String)")
            extraction.assert().isEqualTo(ColumnExtraction.JsonArray("state", "values"))
        }
        plan.diagnostics.single().run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.UNSUPPORTED_TYPE_FALLBACK)
            message.contains("java.util.List<java.lang.Thread>").assert().isTrue()
        }
    }

    @Test
    fun `should reject unknown collection element before building child view`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner().plan(rawCollectionAggregateMetadata)
        }.hasMessageContaining("bi-service.raw-collection")
            .hasMessageContaining("values")
            .hasMessageContaining("java.util.List<java.lang.Object>")
    }

    @Test
    fun `should preserve unknown collection as raw string array with diagnostic`() {
        val plan = StateExpansionPlanner(
            BiScriptOptions(unsupportedTypeStrategy = UnsupportedTypeStrategy.STRING_WITH_DIAGNOSTIC)
        ).plan(rawCollectionAggregateMetadata)

        plan.views.assert().hasSize(1)
        plan.views.single().columns.single { it.targetName == "values" }.run {
            sqlType.assert().isEqualTo("Array(String)")
            extraction.assert().isEqualTo(ColumnExtraction.JsonArray("state", "values"))
        }
        plan.diagnostics.single().run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.UNSUPPORTED_TYPE_FALLBACK)
            message.contains("java.util.List<java.lang.Object>").assert().isTrue()
        }
    }

    @Test
    fun `should prioritize depth truncation over unsupported nested collection elements`() {
        val plan = StateExpansionPlanner(BiScriptOptions(maxExpansionDepth = 1))
            .plan(truncatedUnsupportedCollectionAggregateMetadata)

        plan.views.assert().hasSize(1)
        val rootColumns = plan.views.single().columns
        rootColumns.first { it.targetName == "nested__platform_values" }.run {
            sqlType.assert().isEqualTo("Array(String)")
            extraction.assert().isEqualTo(ColumnExtraction.JsonArray("nested", "platformValues"))
        }
        rootColumns.first { it.targetName == "nested__raw_values" }.run {
            sqlType.assert().isEqualTo("Array(String)")
            extraction.assert().isEqualTo(ColumnExtraction.JsonArray("nested", "rawValues"))
        }
        plan.diagnostics.map { it.path to it.code }.assert().containsExactly(
            "nested.platformValues" to BiScriptDiagnosticCode.MAX_DEPTH_REACHED,
            "nested.rawValues" to BiScriptDiagnosticCode.MAX_DEPTH_REACHED,
        )
        plan.diagnostics.none { it.code == BiScriptDiagnosticCode.UNSUPPORTED_TYPE_FALLBACK }.assert().isTrue()
    }

    @Test
    fun `should expose java unmodifiable plan collections`() {
        val plan = StateExpansionPlanner().plan(biAggregateMetadata)
        val rootColumns = plan.views.first().columns

        assertAll(
            {
                assertJavaUnmodifiable(plan.views, plan.views.first())
            },
            {
                assertJavaUnmodifiable(rootColumns, rootColumns.first())
            },
            {
                assertJavaUnmodifiable(plan.diagnostics, plan.diagnostics.first())
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> assertJavaUnmodifiable(values: List<T>, element: T) {
        assertThrownBy<UnsupportedOperationException> {
            (values as MutableList<T>).add(element)
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

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class NonStringMapAggregate(private val state: NonStringMapState)

private class NonStringMapState(override val id: String) : Identifier {
    val values: Map<Int, String> = emptyMap()
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class RawMapAggregate(private val state: RawMapState)

private class RawMapState(override val id: String) : Identifier {
    val values: Map<*, *> = emptyMap<Any, Any>()
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class GenericObjectMapAggregate(private val state: GenericObjectMapState)

private class GenericObjectMapState(override val id: String) : Identifier {
    val values: Map<String, List<Item>> = emptyMap()
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class PlatformCollectionAggregate(private val state: PlatformCollectionState)

private class PlatformCollectionState(override val id: String) : Identifier {
    val values: List<Thread> = emptyList()
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class RawCollectionAggregate(private val state: RawCollectionState)

private class RawCollectionState(override val id: String) : Identifier {
    val values: List<*> = emptyList<Any>()
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class TruncatedUnsupportedCollectionAggregate(private val state: TruncatedUnsupportedCollectionState)

private class TruncatedUnsupportedCollectionState(override val id: String) : Identifier {
    val nested: NestedUnsupportedCollections = NestedUnsupportedCollections()
}

private data class NestedUnsupportedCollections(
    val platformValues: List<Thread> = emptyList(),
    val rawValues: List<*> = emptyList<Any>(),
)

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class TruncatedPlatformObjectAggregate(private val state: TruncatedPlatformObjectState)

private class TruncatedPlatformObjectState(override val id: String) : Identifier {
    val nested: NestedPlatformObject = NestedPlatformObject()
}

private data class NestedPlatformObject(val value: Thread = Thread.currentThread())

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class TruncatedObjectMapAggregate(private val state: TruncatedObjectMapState)

private class TruncatedObjectMapState(override val id: String) : Identifier {
    val nested: NestedObjectMap = NestedObjectMap()
}

private data class NestedObjectMap(val values: Map<String, Item> = emptyMap())

private val siblingAggregateMetadata = aggregateMetadata<SiblingAggregate, SiblingState>()
private val unsupportedAggregateMetadata = aggregateMetadata<UnsupportedAggregate, UnsupportedState>()
private val nonStringMapAggregateMetadata = aggregateMetadata<NonStringMapAggregate, NonStringMapState>()
private val rawMapAggregateMetadata = aggregateMetadata<RawMapAggregate, RawMapState>()
private val genericObjectMapAggregateMetadata = aggregateMetadata<GenericObjectMapAggregate, GenericObjectMapState>()
private val platformCollectionAggregateMetadata =
    aggregateMetadata<PlatformCollectionAggregate, PlatformCollectionState>()
private val rawCollectionAggregateMetadata = aggregateMetadata<RawCollectionAggregate, RawCollectionState>()
private val truncatedUnsupportedCollectionAggregateMetadata =
    aggregateMetadata<TruncatedUnsupportedCollectionAggregate, TruncatedUnsupportedCollectionState>()
private val truncatedPlatformObjectAggregateMetadata =
    aggregateMetadata<TruncatedPlatformObjectAggregate, TruncatedPlatformObjectState>()
private val truncatedObjectMapAggregateMetadata =
    aggregateMetadata<TruncatedObjectMapAggregate, TruncatedObjectMapState>()
