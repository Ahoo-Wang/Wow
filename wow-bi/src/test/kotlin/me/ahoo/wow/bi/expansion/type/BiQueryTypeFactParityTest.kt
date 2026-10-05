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

package me.ahoo.wow.bi.expansion.type

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.bi.expansion.plan.isUnsupportedPlatformObject
import me.ahoo.wow.bi.type.ClickHouseTypeMapping.scalarMapping
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.query.schema.QueryTypeFact
import me.ahoo.wow.schema.query.JsonQueryModelSource
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.viewstore.domain.preferences.ViewPreferencesState
import me.ahoo.wow.viewstore.domain.view.ViewState
import org.junit.jupiter.api.Test

/**
 * BI resolves state types with its own engine ([JsonPropertyTypeResolver] + [JacksonWireShapeInspector]); the query
 * model and OpenAPI resolve them with [JsonQueryModelSource]. This test walks both over the example, compensation and
 * view-store states and compares, per serialized path, the property names, the value kind and the nullability.
 *
 * The divergences below are the known ones; a new divergence (or one that disappears) fails the test, so a change to
 * either engine that moves them apart, or together, is seen in review.
 */
class BiQueryTypeFactParityTest {

    @Test
    fun `BI and the query model agree on the state types except for the known divergences`() {
        val divergences = STATE_TYPES.flatMap { stateType ->
            val root = ResolvedType(JsonSerializer.constructType(stateType), Nullability.NON_NULL, emptyList())
            compare(stateType.simpleName, root, JsonQueryModelSource().describe(stateType))
        }

        divergences.assert().containsExactlyInAnyOrderElementsOf(KNOWN_DIVERGENCES)
    }

    @Test
    fun `BI and the query model see the same property names`() {
        STATE_TYPES.flatMap { stateType ->
            val root = ResolvedType(JsonSerializer.constructType(stateType), Nullability.NON_NULL, emptyList())
            compare(stateType.simpleName, root, JsonQueryModelSource().describe(stateType))
        }.filter { it.contains("ABSENT") }.assert().isEmpty()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun compare(path: String, type: ResolvedType, queryFact: QueryTypeFact): List<String> = buildList {
        val fact = queryFact.withoutNull()
        val biKind = type.biKind()
        val queryKind = fact.kind.toBiKind()
        if (biKind != queryKind) {
            add("$path kind: bi=$biKind query=$queryKind")
            return@buildList
        }
        val biNullable = when (type.nullability) {
            Nullability.NON_NULL -> false
            Nullability.NULLABLE -> true
            Nullability.UNKNOWN -> null
        }
        if (biNullable != null && fact.nullable != null && biNullable != fact.nullable) {
            add("$path nullable: bi=$biNullable query=${fact.nullable}")
        }
        when (biKind) {
            BiKind.OBJECT -> if (type.javaType.isMapLikeType) {
                val value = type.arguments.getOrNull(1)
                val additional = fact.additionalProperties
                if (value != null && additional != null) addAll(compare("$path{}", value, additional))
            } else {
                val biProperties = (JacksonWireShapeInspector.inspect(type) as JsonWireShape.ExpandableObject)
                    .properties.associateBy(ResolvedJsonProperty::serializedName)
                (biProperties.keys + fact.properties.keys).sorted().forEach { name ->
                    val bi = biProperties[name]
                    val query = fact.properties[name]
                    when {
                        bi == null -> add("$path.$name ABSENT in bi")
                        query == null -> add("$path.$name ABSENT in query")
                        else -> addAll(compare("$path.$name", bi.type, query))
                    }
                }
            }

            BiKind.ARRAY -> {
                val element = type.arguments.firstOrNull()
                val items = fact.items
                if (element != null && items != null) addAll(compare("$path[]", element, items))
            }

            else -> Unit
        }
    }

    /** A nullable value is a union of `null` and its value in the query model; BI keeps the nullability apart. */
    private fun QueryTypeFact.withoutNull(): QueryTypeFact {
        if (kind != QueryValueKind.UNION) return this
        val value = alternatives.filterNot { it.kind == QueryValueKind.NULL }.singleOrNull() ?: return this
        return QueryTypeFact(
            kind = value.kind,
            nullable = true,
            properties = value.properties,
            items = value.items,
            additionalProperties = value.additionalProperties,
        )
    }

    /** How BI's planner treats the type ([me.ahoo.wow.bi.expansion.plan.StateExpansionPropertyCollector]). */
    private fun ResolvedType.biKind(): BiKind {
        rawClass.scalarMapping()?.let { mapping ->
            return if (JacksonWireShapeInspector.matches(this, mapping.tokenShape)) BiKind.SCALAR else BiKind.RAW
        }
        return when {
            javaType.isMapLikeType -> BiKind.OBJECT
            javaType.isCollectionLikeType || javaType.isArrayType -> BiKind.ARRAY
            isUnsupportedPlatformObject(this) -> BiKind.RAW
            JacksonWireShapeInspector.inspect(this) is JsonWireShape.ExpandableObject -> BiKind.OBJECT
            else -> BiKind.RAW
        }
    }

    private fun QueryValueKind.toBiKind(): BiKind = when (this) {
        QueryValueKind.SCALAR -> BiKind.SCALAR
        QueryValueKind.OBJECT -> BiKind.OBJECT
        QueryValueKind.ARRAY -> BiKind.ARRAY
        // BI keeps a value it cannot verify as raw JSON; the query model calls it unknown.
        QueryValueKind.UNKNOWN -> BiKind.RAW
        QueryValueKind.NULL, QueryValueKind.UNION -> BiKind.UNION
    }

    private enum class BiKind { SCALAR, OBJECT, ARRAY, RAW, UNION }

    private companion object {
        val STATE_TYPES = listOf(
            OrderState::class.java,
            CartState::class.java,
            ExecutionFailedState::class.java,
            ViewState::class.java,
            ViewPreferencesState::class.java,
        )

        val KNOWN_DIVERGENCES = listOf(
            // BigDecimal: BI keeps it as raw JSON (lossless), the query model a number scalar.
            "OrderState.items[].price kind: bi=RAW query=SCALAR",
            "OrderState.items[].totalPrice kind: bi=RAW query=SCALAR",
            "OrderState.paidAmount kind: bi=RAW query=SCALAR",
            "OrderState.payable kind: bi=RAW query=SCALAR",
            "OrderState.totalAmount kind: bi=RAW query=SCALAR",
            // AggregateId has a custom serializer: BI does not expand it, the query model describes its object shape.
            "ExecutionFailedState.eventId.aggregateId kind: bi=RAW query=OBJECT",
            // An enum serialized through @JsonValue: BI keeps it raw, the query model sees the string.
            "ViewState.audience kind: bi=RAW query=SCALAR",
            // `String?` with @JsonInclude(NON_NULL): JSON never holds null (absent instead). BI reads the Kotlin type.
            "ExecutionFailedState.error.bindingErrors[].code nullable: bi=true query=false",
        )
    }
}
