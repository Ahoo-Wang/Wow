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

import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.bi.BiScriptDiagnosticCode
import me.ahoo.wow.bi.BiScriptMappingDecision
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.bi.expansion.BIAggregate
import me.ahoo.wow.bi.expansion.BIAggregateState
import me.ahoo.wow.bi.expansion.Item
import me.ahoo.wow.bi.expansion.type.JavaNullabilityFixture
import me.ahoo.wow.bi.type.ClickHouseType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.JavaType
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import tools.jackson.databind.ser.std.StdSerializer

class StateExpansionPlannerTest {
    private val biAggregateMetadata = aggregateMetadata<BIAggregate, BIAggregateState>()

    @Test
    fun `should plan immutable root and nested collection recovery coordinates`() {
        val plan = StateExpansionPlanner().plan(biAggregateMetadata)
        val root = plan.views.single { it.targetTableName.endsWith("_root") }
        val child = plan.views.single { it.targetTableName.endsWith("_root_nested_list") }
        val nestedChild = plan.views.single { it.targetTableName.endsWith("_root_nested_list_list") }
        val outerCursor = alias("__cursor__nested_list")
        val innerCursor = alias("__cursor__nested_list__list")

        root.recovery.pointer.assert().isEmpty()
        root.recovery.currentIndex.assert().isNull()
        root.recovery.cursors.assert().isEmpty()

        child.recovery.pointer.assert().containsExactly(
            JsonPointerSegment.Property("nestedList"),
            JsonPointerSegment.Index(outerCursor),
        )
        child.recovery.currentIndex.assert().isEqualTo(outerCursor)
        child.recovery.cursors.assert().containsExactly(
            CollectionCursorPlan(
                source = input("state"),
                property = "nestedList",
                cursor = outerCursor,
                element = alias("nested_list"),
            )
        )

        nestedChild.recovery.pointer.assert().containsExactly(
            JsonPointerSegment.Property("nestedList"),
            JsonPointerSegment.Index(outerCursor),
            JsonPointerSegment.Property("list"),
            JsonPointerSegment.Index(innerCursor),
        )
        nestedChild.recovery.currentIndex.assert().isEqualTo(innerCursor)
        nestedChild.recovery.cursors.assert().containsExactly(
            CollectionCursorPlan(
                source = input("state"),
                property = "nestedList",
                cursor = outerCursor,
                element = alias("nested_list"),
            ),
            CollectionCursorPlan(
                source = alias("nested_list"),
                property = "list",
                cursor = innerCursor,
                element = alias("nested_list__list"),
            ),
        )

        assertAll(
            {
                assertJavaUnmodifiable(
                    child.recovery.pointer,
                    JsonPointerSegment.Property("forbidden"),
                )
            },
            {
                assertJavaUnmodifiable(
                    child.recovery.cursors,
                    child.recovery.cursors.single(),
                )
            },
        )
    }

    @Test
    fun `should encode serialized collection property as RFC 6901 pointer segment`() {
        val child = StateExpansionPlanner().plan(escapedPathAggregateMetadata)
            .views.single { it.recovery.currentIndex != null }

        child.recovery.pointer.assert().containsExactly(
            JsonPointerSegment.Property("a~1b~0c"),
            JsonPointerSegment.Index(checkNotNull(child.recovery.currentIndex)),
        )
    }

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
        rootView.column("item").run {
            type.assert().isEqualTo(ClickHouseType.String)
            placement.assert().isEqualTo(ColumnPlacement.WITH)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "item"))
        }
        rootView.column("item__id").run {
            type.assert().isEqualTo(ClickHouseType.String)
            placement.assert().isEqualTo(ColumnPlacement.SELECT)
            extraction.assert().isEqualTo(ColumnExtraction.JsonValue(alias("item"), "id"))
        }
        rootView.column("items").run {
            type.assert().isEqualTo(ClickHouseType.Array(ClickHouseType.String))
            placement.assert().isEqualTo(ColumnPlacement.SELECT)
            extraction.assert().isEqualTo(ColumnExtraction.JsonArray(input("state"), "items"))
            inherited.assert().isFalse()
        }
        rootView.column("like_link_string").type.assert()
            .isEqualTo(ClickHouseType.Array(ClickHouseType.String))
        rootView.column("like_map_string").type.assert().isEqualTo(
            ClickHouseType.Map(ClickHouseType.String, ClickHouseType.String)
        )
        plan.views.single { it.targetTableName.endsWith("_like_list_item") }.run {
            columns.none { it.targetName == "__raw__like_list_item" }.assert().isTrue()
            column("like_list_item__id").type.assert().isEqualTo(ClickHouseType.String)
            column("like_list_item__name").type.assert().isEqualTo(ClickHouseType.String)
        }
        rootView.column("map_item").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "mapItem"))
        }
        plan.diagnostics.single { it.path == "mapItem" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.RAW_JSON_FALLBACK)
            aggregate.assert().isEqualTo("bi-service.aggregate")
            sourceType.contains(Item::class.java.name).assert().isTrue()
            decision.assert().isEqualTo(BiScriptMappingDecision.RAW_JSON)
            message.assert().contains("scoped JSON").contains("__state")
        }
    }

    @Test
    fun `should preserve scalar values according to their verified JSON wire shapes`() {
        val plan = StateExpansionPlanner().plan(biAggregateMetadata)
        val root = plan.views.first()

        root.column("big_decimal").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "bigDecimal"))
        }
        root.column("duration").type.assert().isEqualTo(ClickHouseType.String)
        root.column("date").type.assert().isEqualTo(ClickHouseType.String)
        root.column("sql_date").type.assert().isEqualTo(ClickHouseType.String)
        root.column("instant").type.assert().isEqualTo(ClickHouseType.String)
        root.column("year").type.assert().isEqualTo(ClickHouseType.Int32)
        root.column("kotlin_duration").type.assert().isEqualTo(ClickHouseType.Int64)
        root.column("default_enum").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonValue(input("state"), "defaultEnum"))
        }
        root.column("numeric_enum").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "numericEnum"))
        }
        plan.diagnostics.single { it.path == "bigDecimal" }.code.assert()
            .isEqualTo(BiScriptDiagnosticCode.RAW_JSON_FALLBACK)
        plan.diagnostics.single { it.path == "numericEnum" }.code.assert()
            .isEqualTo(BiScriptDiagnosticCode.RAW_JSON_FALLBACK)
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
    fun `should keep depth-cut object as one whole raw value`() {
        val plan = StateExpansionPlanner(BiScriptOptions(maxExpansionDepth = 1)).plan(biAggregateMetadata)
        val rootView = plan.views.first()

        rootView.column("nested__child").run {
            type.assert().isEqualTo(ClickHouseType.String)
            placement.assert().isEqualTo(ColumnPlacement.SELECT)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(alias("nested"), "child"))
        }
        rootView.column("nested__id").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonValue(alias("nested"), "id"))
        }
        rootView.columns.none { it.targetName == "nested__child__id" }.assert().isTrue()
        plan.diagnostics.single { it.path == "nested.child" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.MAX_DEPTH_REACHED)
            sourceType.assert().contains("NestedChild")
            decision.assert().isEqualTo(BiScriptMappingDecision.MAX_DEPTH_RAW_JSON)
        }
    }

    @Test
    fun `should keep depth-cut object collection as one whole raw value`() {
        val plan = StateExpansionPlanner(BiScriptOptions(maxExpansionDepth = 1)).plan(siblingAggregateMetadata)
        val rootView = plan.views.first()

        rootView.column("alpha__children").run {
            type.assert().isEqualTo(ClickHouseType.String)
            placement.assert().isEqualTo(ColumnPlacement.SELECT)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(alias("alpha"), "children"))
        }
        plan.views.none { it.targetTableName.endsWith("_alpha__children") }.assert().isTrue()
        plan.diagnostics.single { it.path == "alpha.children" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.MAX_DEPTH_REACHED)
            decision.assert().isEqualTo(BiScriptMappingDecision.MAX_DEPTH_RAW_JSON)
        }
    }

    @Test
    fun `should include aggregate path and actual type in strict object map failure`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner(
                BiScriptOptions(unsupportedTypeStrategy = UnsupportedTypeStrategy.FAIL)
            ).plan(genericObjectMapAggregateMetadata)
        }.hasMessageContaining("bi-service.generic-object-map")
            .hasMessageContaining("genericValues")
            .hasMessageContaining("java.util.List<me.ahoo.wow.bi.expansion.Item>")
    }

    @Test
    fun `should include aggregate path and actual type in strict unsupported failure`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner(
                BiScriptOptions(unsupportedTypeStrategy = UnsupportedTypeStrategy.FAIL)
            ).plan(nonStringMapAggregateMetadata)
        }.hasMessageContaining("bi-service.non-string-map")
            .hasMessageContaining("aUnsupported")
            .hasMessageContaining(Thread::class.java.name)
    }

    @Test
    fun `should preserve unsupported platform object as raw json with structured diagnostic`() {
        val plan = StateExpansionPlanner().plan(nonStringMapAggregateMetadata)
        val rootView = plan.views.single()

        rootView.column("a_unsupported").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "aUnsupported"))
        }
        plan.diagnostics.single { it.path == "aUnsupported" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.RAW_JSON_FALLBACK)
            aggregate.assert().isEqualTo("bi-service.non-string-map")
            path.assert().isEqualTo("aUnsupported")
            sourceType.assert().isEqualTo(Thread::class.java.name)
            decision.assert().isEqualTo(BiScriptMappingDecision.RAW_JSON)
        }
    }

    @Test
    fun `should preserve Java Kotlin contract generic nullability structurally`() {
        val root = StateExpansionPlanner().plan(javaContractAggregateMetadata).views.single()

        root.column("non_null_values").type.assert()
            .isEqualTo(ClickHouseType.Array(ClickHouseType.String))
        root.column("nullable_element_values").type.assert().isEqualTo(
            ClickHouseType.Array(ClickHouseType.Nullable(ClickHouseType.String))
        )
        root.columns.none { it.targetName == "__raw__non_null_values" }.assert().isTrue()
        root.columns.none { it.targetName == "__raw__nullable_element_values" }.assert().isTrue()
    }

    @Test
    fun `should preserve Java platform list element as nullable`() {
        val root = StateExpansionPlanner().plan(platformCollectionAggregateMetadata).views.single()

        root.column("platform_list").type.assert().isEqualTo(
            ClickHouseType.Array(ClickHouseType.Nullable(ClickHouseType.String))
        )
        root.columns.none { it.targetName == "__raw__platform_list" }.assert().isTrue()
    }

    @Test
    fun `should preserve Java platform map as one whole raw value`() {
        val plan = StateExpansionPlanner().plan(platformCollectionAggregateMetadata)
        val root = plan.views.single()

        root.column("platform_map").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "platformMap"))
        }
        root.columns.filter { it.path == "platformMap" }.assert().hasSize(1)
        root.columns.none { it.targetName == "__raw__platform_map" }.assert().isTrue()
        plan.diagnostics.single { it.path == "platformMap" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.RAW_JSON_FALLBACK)
            sourceType.assert().contains("StringMap")
            decision.assert().isEqualTo(BiScriptMappingDecision.RAW_JSON)
        }
    }

    @Test
    fun `should reject Java platform map key under strict strategy`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner(
                BiScriptOptions(unsupportedTypeStrategy = UnsupportedTypeStrategy.FAIL)
            ).plan(platformCollectionAggregateMetadata)
        }.hasMessageContaining("bi-service.platform-collection")
            .hasMessageContaining("platformMap")
            .hasMessageContaining("StringMap")
    }

    @Test
    fun `should keep Java generic Kotlin contract element nullable`() {
        val root = StateExpansionPlanner().plan(javaContractAggregateMetadata).views.single()

        root.column("generic_values").type.assert().isEqualTo(
            ClickHouseType.Array(ClickHouseType.Nullable(ClickHouseType.String))
        )
        root.columns.none { it.targetName == "__raw__generic_values" }.assert().isTrue()
    }

    @Test
    fun `should preserve nested Java generic values structurally`() {
        val plan = StateExpansionPlanner().plan(javaGenericBoxAggregateMetadata)
        val root = plan.views.single()

        root.column("scalar_box__value").type.assert().isEqualTo(ClickHouseType.String)
        root.column("list_box__value").type.assert().isEqualTo(
            ClickHouseType.Array(ClickHouseType.Nullable(ClickHouseType.String))
        )
        root.column("map_box__value").type.assert().isEqualTo(
            ClickHouseType.Map(ClickHouseType.String, ClickHouseType.Int32)
        )
        plan.diagnostics.assert().isEmpty()
    }

    @Test
    fun `should preserve nullable and unknown map keys as one whole raw value`() {
        val kotlinPlan = StateExpansionPlanner().plan(nonStringMapAggregateMetadata)
        val kotlinRoot = kotlinPlan.views.single()
        kotlinRoot.column("nullable_key_values").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "nullableKeyValues"))
        }
        kotlinRoot.columns.filter { it.path == "nullableKeyValues" }.assert().hasSize(1)
        kotlinRoot.columns.none { it.targetName == "__raw__nullable_key_values" }.assert().isTrue()
        kotlinPlan.diagnostics.single { it.path == "nullableKeyValues" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.RAW_JSON_FALLBACK)
            sourceType.assert().isEqualTo("java.util.Map<java.lang.String,java.lang.Integer>")
            decision.assert().isEqualTo(BiScriptMappingDecision.RAW_JSON)
        }

        val javaPlan = StateExpansionPlanner().plan(javaContractAggregateMetadata)
        val javaRoot = javaPlan.views.single()
        javaRoot.column("unknown_key_values").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "unknownKeyValues"))
        }
        javaRoot.columns.filter { it.path == "unknownKeyValues" }.assert().hasSize(1)
        javaRoot.columns.none { it.targetName == "__raw__unknown_key_values" }.assert().isTrue()
        javaPlan.diagnostics.single { it.path == "unknownKeyValues" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.RAW_JSON_FALLBACK)
            sourceType.assert().isEqualTo("java.util.Map<java.lang.String,java.lang.Integer>")
            decision.assert().isEqualTo(BiScriptMappingDecision.RAW_JSON)
        }
    }

    @Test
    fun `should reject unknown Java map key under strict strategy`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner(
                BiScriptOptions(unsupportedTypeStrategy = UnsupportedTypeStrategy.FAIL)
            ).plan(javaContractAggregateMetadata)
        }.hasMessageContaining("bi-service.unsupported")
            .hasMessageContaining("unknownKeyValues")
            .hasMessageContaining("java.util.Map<java.lang.String,java.lang.Integer>")
    }

    @Test
    fun `should preserve non-string map as one raw json value`() {
        val plan = StateExpansionPlanner().plan(nonStringMapAggregateMetadata)

        plan.views.single().column("non_string_values").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "nonStringValues"))
        }
        plan.diagnostics.single { it.path == "nonStringValues" }.run {
            code.assert().isEqualTo(BiScriptDiagnosticCode.RAW_JSON_FALLBACK)
            sourceType.assert().isEqualTo("java.util.Map<java.lang.Integer,java.lang.String>")
            decision.assert().isEqualTo(BiScriptMappingDecision.RAW_JSON)
        }
    }

    @Test
    fun `should preserve raw map as one raw json value`() {
        val plan = StateExpansionPlanner().plan(nonStringMapAggregateMetadata)

        plan.views.single().column("raw_values").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "rawValues"))
        }
        plan.diagnostics.single { it.path == "rawValues" }.sourceType.assert()
            .isEqualTo("java.util.Map<java.lang.Object,java.lang.Object>")
    }

    @Test
    fun `should preserve heterogeneous object map as one whole raw value`() {
        val plan = StateExpansionPlanner().plan(genericObjectMapAggregateMetadata)
        val rootView = plan.views.single()

        rootView.columns.filter { it.path == "values" }.assert().hasSize(1)
        rootView.column("values").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "values"))
        }
        rootView.columns.none {
            it.type == ClickHouseType.Map(ClickHouseType.String, ClickHouseType.String)
        }.assert().isTrue()
    }

    @Test
    fun `should preserve unsupported collection as one whole raw value`() {
        val plan = StateExpansionPlanner().plan(platformCollectionAggregateMetadata)

        plan.views.assert().hasSize(1)
        plan.views.single().column("platform_values").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "platformValues"))
        }
        plan.diagnostics.single { it.path == "platformValues" }.sourceType.assert()
            .isEqualTo("java.util.List<java.lang.Thread>")
    }

    @Test
    fun `should preserve raw generic collection as one whole raw value`() {
        val plan = StateExpansionPlanner().plan(platformCollectionAggregateMetadata)

        plan.views.assert().hasSize(1)
        plan.views.single().column("raw_values").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "rawValues"))
        }
        plan.diagnostics.single { it.path == "rawValues" }.sourceType.assert()
            .isEqualTo("java.util.List<java.lang.Object>")
    }

    @Test
    fun `should preserve custom serialized containers as raw JSON`() {
        val plan = StateExpansionPlanner().plan(platformCollectionAggregateMetadata)
        val root = plan.views.single()

        root.column("z_custom_list").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "zCustomList"))
        }
        root.column("z_custom_map").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "zCustomMap"))
        }
        plan.diagnostics.map { it.path }.assert().contains("zCustomList", "zCustomMap")
    }

    @Test
    fun `should not trust custom container visitor content claims`() {
        val plan = StateExpansionPlanner().plan(platformCollectionAggregateMetadata)
        val root = plan.views.single()

        root.column("z_misleading_list").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "zMisleadingList"))
        }
        root.column("z_misleading_map").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "zMisleadingMap"))
        }
    }

    @Test
    fun `should prioritize depth cutoff and retain whole unsupported collections`() {
        val plan = StateExpansionPlanner(BiScriptOptions(maxExpansionDepth = 1))
            .plan(truncatedUnsupportedCollectionAggregateMetadata)

        plan.views.assert().hasSize(1)
        val rootColumns = plan.views.single().columns
        rootColumns.single { it.targetName == "nested__platform_values" }.run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(alias("nested"), "platformValues"))
        }
        rootColumns.single { it.targetName == "nested__raw_values" }.run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(alias("nested"), "rawValues"))
        }
        plan.diagnostics.map { it.path to it.code }.assert().containsExactly(
            "nested.platformValues" to BiScriptDiagnosticCode.MAX_DEPTH_REACHED,
            "nested.rawValues" to BiScriptDiagnosticCode.MAX_DEPTH_REACHED,
        )
        plan.diagnostics.all {
            it.decision == BiScriptMappingDecision.MAX_DEPTH_RAW_JSON
        }.assert().isTrue()
    }

    @Test
    fun `should reject duplicate normalized target names before rendering`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner().plan(duplicateTargetAggregateMetadata)
        }.hasMessageContaining("foo_bar")
            .hasMessageContaining("duplicate")
    }

    @Test
    fun `should reject domain property colliding with raw companion namespace`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner().plan(rawCompanionCollisionAggregateMetadata)
        }.hasMessageContaining("__raw__name")
            .hasMessageContaining("collision")
    }

    @Test
    fun `should reject domain property colliding with metadata alias`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner().plan(metadataAliasCollisionAggregateMetadata)
        }.hasMessageContaining("__id")
            .hasMessageContaining("collision")
    }

    @Test
    fun `should reject domain properties colliding with recovery aliases`() {
        listOf(
            stateAliasCollisionAggregateMetadata to "__state",
            pathAliasCollisionAggregateMetadata to "__path",
            indexAliasCollisionAggregateMetadata to "__index",
            cursorAliasCollisionAggregateMetadata to "__cursor__items",
        ).forEach { (aggregate, target) ->
            assertThrownBy<IllegalArgumentException> {
                StateExpansionPlanner().plan(aggregate)
            }.hasMessageContaining(target)
                .hasMessageContaining("collision")
        }
    }

    @Test
    fun `should expose Java unmodifiable deterministic plan collections`() {
        val plan = StateExpansionPlanner().plan(biAggregateMetadata)
        val rootColumns = plan.views.first().columns

        assertAll(
            { assertJavaUnmodifiable(plan.views, plan.views.first()) },
            { assertJavaUnmodifiable(rootColumns, rootColumns.first()) },
            {
                assertJavaUnmodifiable(
                    plan.views.first().recovery.pointer,
                    JsonPointerSegment.Property("forbidden"),
                )
            },
            { assertJavaUnmodifiable(plan.diagnostics, plan.diagnostics.first()) },
        )
    }

    @Test
    fun `should preserve an opaque nested value as one raw column`() {
        val plan = StateExpansionPlanner().plan(opaquePropertyAggregateMetadata)
        val root = plan.views.single()

        root.column("payment").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "payment"))
        }
        root.columns.filter { it.path == "payment" }.assert().hasSize(1)
        root.column("name").type.assert().isEqualTo(ClickHouseType.String)
        plan.diagnostics.single { it.path == "payment" }.decision.assert()
            .isEqualTo(BiScriptMappingDecision.RAW_JSON)
    }

    @Test
    fun `should fail for an opaque nested value in strict mode`() {
        assertThrownBy<IllegalArgumentException> {
            StateExpansionPlanner(
                BiScriptOptions(unsupportedTypeStrategy = UnsupportedTypeStrategy.FAIL)
            ).plan(opaquePropertyAggregateMetadata)
        }.hasMessageContaining("payment")
            .hasMessageContaining(OpaquePayment::class.java.name)
    }

    @Test
    fun `should preserve a collection with opaque elements as one raw column`() {
        val plan = StateExpansionPlanner().plan(opaquePropertyAggregateMetadata)
        val root = plan.views.single()

        root.column("payments").run {
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.JsonRaw(input("state"), "payments"))
        }
        plan.views.none { it.targetTableName.endsWith("_payments") }.assert().isTrue()
        plan.diagnostics.single { it.path == "payments" }.decision.assert()
            .isEqualTo(BiScriptMappingDecision.RAW_JSON)
    }

    @Test
    fun `should preserve an opaque root state as one raw column`() {
        val plan = StateExpansionPlanner().plan(opaqueRootAggregateMetadata)
        val root = plan.views.single()

        root.columns.assert().hasSize(1)
        root.column("state").run {
            path.assert().isEqualTo("$")
            type.assert().isEqualTo(ClickHouseType.String)
            extraction.assert().isEqualTo(ColumnExtraction.Reference(input("state")))
        }
        plan.diagnostics.single().run {
            path.assert().isEqualTo("$")
            decision.assert().isEqualTo(BiScriptMappingDecision.RAW_JSON)
        }
    }

    private fun ExpansionViewPlan.column(targetName: String): ColumnPlan =
        columns.single { it.targetName == targetName }

    private fun input(name: String): ColumnReference = ColumnReference.Input(name)

    private fun alias(name: String): ColumnReference = ColumnReference.Alias(name)

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
private class UnsupportedAggregate(
    private val state: JavaNullabilityFixture.KotlinGenericContractState,
)

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class JavaGenericBoxAggregate(
    private val state: JavaNullabilityFixture.GenericBoxState,
)

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class NonStringMapAggregate(private val state: NonStringMapState)

private class NonStringMapState(override val id: String) : Identifier {
    val aUnsupported: Thread = Thread.currentThread()
    val nonStringValues: Map<Int, String> = emptyMap()
    val nullableKeyValues: Map<String?, Int> = emptyMap()
    val rawValues: Map<*, *> = emptyMap<Any, Any>()
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class RawMapAggregate(private val state: RawMapState)

private class RawMapState(override val id: String) : Identifier {
    val fooBar: String = ""

    @get:JsonProperty("foo_bar")
    val duplicate: String = ""
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class GenericObjectMapAggregate(private val state: GenericObjectMapState)

private class GenericObjectMapState(override val id: String) : Identifier {
    val genericValues: Map<String, List<Item>> = emptyMap()
    val values: Map<String, Any?> = mapOf(
        "string" to "value",
        "number" to 1,
        "boolean" to true,
        "null" to null,
        "object" to Item("id", "name"),
        "array" to listOf(1, 2),
    )
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class PlatformCollectionAggregate(private val state: PlatformCollectionState)

private class PlatformCollectionState(override val id: String) : Identifier {
    val platformList: JavaNullabilityFixture.StringList = JavaNullabilityFixture.StringList()
    val platformMap: JavaNullabilityFixture.StringMap = JavaNullabilityFixture.StringMap()
    val platformValues: List<Thread> = emptyList()
    val rawValues: List<*> = emptyList<Any>()
    val zCustomList: CustomSerializedStringList = CustomSerializedStringList()
    val zCustomMap: CustomSerializedStringMap = CustomSerializedStringMap()
    val zMisleadingList: MisleadingIntegerList = MisleadingIntegerList()
    val zMisleadingMap: MisleadingIntegerMap = MisleadingIntegerMap()
}

@JsonSerialize(using = CustomSerializedStringListSerializer::class)
private class CustomSerializedStringList : ArrayList<String>()

private class CustomSerializedStringListSerializer :
    StdSerializer<CustomSerializedStringList>(CustomSerializedStringList::class.java) {
    override fun serialize(
        value: CustomSerializedStringList,
        generator: JsonGenerator,
        provider: SerializationContext,
    ) {
        generator.writeString(value.joinToString())
    }
}

@JsonSerialize(using = CustomSerializedStringMapSerializer::class)
private class CustomSerializedStringMap : HashMap<String, String>()

private class CustomSerializedStringMapSerializer :
    StdSerializer<CustomSerializedStringMap>(CustomSerializedStringMap::class.java) {
    override fun serialize(
        value: CustomSerializedStringMap,
        generator: JsonGenerator,
        provider: SerializationContext,
    ) {
        generator.writeString(value.entries.joinToString())
    }
}

@JsonSerialize(using = MisleadingIntegerListSerializer::class)
private class MisleadingIntegerList : ArrayList<Int>()

private class MisleadingIntegerListSerializer :
    StdSerializer<MisleadingIntegerList>(MisleadingIntegerList::class.java) {
    override fun serialize(
        value: MisleadingIntegerList,
        generator: JsonGenerator,
        provider: SerializationContext,
    ) {
        generator.writeStartArray()
        value.forEach { generator.writeString(it.toString()) }
        generator.writeEndArray()
    }

    override fun acceptJsonFormatVisitor(visitor: JsonFormatVisitorWrapper, typeHint: JavaType) {
        visitor.expectArrayFormat(typeHint)
    }
}

@JsonSerialize(using = MisleadingIntegerMapSerializer::class)
private class MisleadingIntegerMap : HashMap<String, Int>()

private class MisleadingIntegerMapSerializer :
    StdSerializer<MisleadingIntegerMap>(MisleadingIntegerMap::class.java) {
    override fun serialize(
        value: MisleadingIntegerMap,
        generator: JsonGenerator,
        provider: SerializationContext,
    ) {
        generator.writeStartObject()
        value.forEach { (key, item) -> generator.writeStringProperty(key, item.toString()) }
        generator.writeEndObject()
    }

    override fun acceptJsonFormatVisitor(visitor: JsonFormatVisitorWrapper, typeHint: JavaType) {
        visitor.expectMapFormat(typeHint)
    }
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class RawCollectionAggregate(private val state: RawCollectionState)

private class RawCollectionState(override val id: String) : Identifier {
    val name: String? = null

    @get:JsonProperty("__raw__name")
    val rawName: String = ""
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class TruncatedUnsupportedCollectionAggregate(
    private val state: TruncatedUnsupportedCollectionState,
)

private class TruncatedUnsupportedCollectionState(override val id: String) : Identifier {
    val nested: NestedUnsupportedCollections = NestedUnsupportedCollections()
}

private data class NestedUnsupportedCollections(
    val platformValues: List<Thread> = emptyList(),
    val rawValues: List<*> = emptyList<Any>(),
)

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class TruncatedObjectMapAggregate(private val state: TruncatedObjectMapState)

private class TruncatedObjectMapState(override val id: String) : Identifier {
    @get:JsonProperty("__id")
    val metadataId: String = ""
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class EscapedPathAggregate(private val state: EscapedPathState)

private class EscapedPathState(override val id: String) : Identifier {
    @get:JsonProperty("a/b~c")
    val items: List<CollectionChild> = emptyList()
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class StateAliasCollisionAggregate(private val state: StateAliasCollisionState)

private class StateAliasCollisionState(override val id: String) : Identifier {
    @get:JsonProperty("__state")
    val value: String = ""
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class PathAliasCollisionAggregate(private val state: PathAliasCollisionState)

private class PathAliasCollisionState(override val id: String) : Identifier {
    @get:JsonProperty("__path")
    val value: String = ""
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class IndexAliasCollisionAggregate(private val state: IndexAliasCollisionState)

private class IndexAliasCollisionState(override val id: String) : Identifier {
    @get:JsonProperty("__index")
    val value: String = ""
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class CursorAliasCollisionAggregate(private val state: CursorAliasCollisionState)

private class CursorAliasCollisionState(override val id: String) : Identifier {
    @get:JsonProperty("__cursor__items")
    val value: String = ""
}

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class OpaquePropertyAggregate(private val state: OpaquePropertyState)

private class OpaquePropertyState(override val id: String) : Identifier {
    val name: String = ""
    val payment: OpaquePayment = OpaqueCardPayment("")
    val payments: List<OpaquePayment> = emptyList()
}

private sealed interface OpaquePayment

private data class OpaqueCardPayment(val lastFour: String) : OpaquePayment

@Suppress("UnusedPrivateProperty")
@AggregateRoot
private class OpaqueRootAggregate(private val state: OpaqueRootState)

private abstract class OpaqueRootState : Identifier

private val siblingAggregateMetadata = aggregateMetadata<SiblingAggregate, SiblingState>()
private val javaContractAggregateMetadata =
    aggregateMetadata<UnsupportedAggregate, JavaNullabilityFixture.KotlinGenericContractState>()
private val javaGenericBoxAggregateMetadata =
    aggregateMetadata<JavaGenericBoxAggregate, JavaNullabilityFixture.GenericBoxState>()
private val nonStringMapAggregateMetadata = aggregateMetadata<NonStringMapAggregate, NonStringMapState>()
private val genericObjectMapAggregateMetadata =
    aggregateMetadata<GenericObjectMapAggregate, GenericObjectMapState>()
private val platformCollectionAggregateMetadata =
    aggregateMetadata<PlatformCollectionAggregate, PlatformCollectionState>()
private val truncatedUnsupportedCollectionAggregateMetadata =
    aggregateMetadata<TruncatedUnsupportedCollectionAggregate, TruncatedUnsupportedCollectionState>()
private val duplicateTargetAggregateMetadata =
    aggregateMetadata<RawMapAggregate, RawMapState>()
private val rawCompanionCollisionAggregateMetadata =
    aggregateMetadata<RawCollectionAggregate, RawCollectionState>()
private val metadataAliasCollisionAggregateMetadata =
    aggregateMetadata<TruncatedObjectMapAggregate, TruncatedObjectMapState>()
private val escapedPathAggregateMetadata =
    aggregateMetadata<EscapedPathAggregate, EscapedPathState>()
private val stateAliasCollisionAggregateMetadata =
    aggregateMetadata<StateAliasCollisionAggregate, StateAliasCollisionState>()
private val pathAliasCollisionAggregateMetadata =
    aggregateMetadata<PathAliasCollisionAggregate, PathAliasCollisionState>()
private val indexAliasCollisionAggregateMetadata =
    aggregateMetadata<IndexAliasCollisionAggregate, IndexAliasCollisionState>()
private val cursorAliasCollisionAggregateMetadata =
    aggregateMetadata<CursorAliasCollisionAggregate, CursorAliasCollisionState>()
private val opaquePropertyAggregateMetadata =
    aggregateMetadata<OpaquePropertyAggregate, OpaquePropertyState>()
private val opaqueRootAggregateMetadata =
    aggregateMetadata<OpaqueRootAggregate, OpaqueRootState>()
