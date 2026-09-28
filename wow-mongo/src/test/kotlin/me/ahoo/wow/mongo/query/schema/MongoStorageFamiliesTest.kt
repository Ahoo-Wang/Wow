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

package me.ahoo.wow.mongo.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.NumericFormat
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.mongo.query.schema.MongoQuerySchemaAdapter.Companion.storageRequirements
import me.ahoo.wow.query.schema.QueryValueSchema
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The adapter reads its capability table from the Catalog's (`storageFamilies`). This pins that table, for every
 * semantic type, declared value types and capability MongoDB evaluates, against [legacyRequirements]: the adapter's
 * own table before it moved (audit wave 4), frozen here. The only difference is F2: a [Temporal.Date] value compares
 * date operands, so it gains exact match and range over a BSON `date`.
 */
class MongoStorageFamiliesTest {
    @Test
    fun `the catalog table reproduces the former adapter table except for date operands`() {
        SEMANTICS.forEach { semantic ->
            VALUE_TYPE_SETS.forEach { valueTypes ->
                val value = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = valueTypes, semanticType = semantic)
                CAPABILITIES.forEach { capability ->
                    val expected = if (semantic == Temporal.Date && capability in DATE_OPERAND_CAPABILITIES) {
                        listOf(setOf("date"))
                    } else {
                        value.legacyRequirements(capability)
                    }
                    value.storageRequirements(capability).assert().describedAs("$semantic $valueTypes $capability")
                        .isEqualTo(expected)
                }
            }
        }
    }

    /** The adapter's table before wave 4, which read no date operand (F2). */
    private fun QueryValueSchema.legacyRequirements(capability: QueryCapability): List<Set<String>> {
        if (semanticType == Temporal.Date && capability in LEGACY_DATE_OPERAND_CAPABILITIES) return emptyList()
        return when (capability) {
            QueryCapability.EXACT_MATCH, QueryCapability.SORT, QueryCapability.CURSOR_SORT, QueryCapability.AGGREGATE_TERMS ->
                temporalRequirements().ifEmpty { valueTypes.map { it.storageTypes() } }
            QueryCapability.LITERAL_MATCH -> if (semanticType is Temporal.Epoch) {
                emptyList()
            } else {
                valueTypes.map { if (it == QueryValueType.STRING) STRING_TYPES else emptySet() }
            }
            QueryCapability.RANGE -> temporalRequirements().ifEmpty {
                valueTypes.map { if (it == QueryValueType.STRING) STRING_TYPES else it.numericTypes() }
            }
            QueryCapability.AGGREGATE_NUMERIC -> valueTypes.map { it.numericTypes() }
            QueryCapability.AGGREGATE_TEMPORAL -> temporalRequirements()
            else -> emptyList()
        }
    }

    private fun QueryValueType.numericTypes(): Set<String> = when (this) {
        QueryValueType.INTEGER -> INTEGRAL_TYPES
        QueryValueType.DECIMAL -> NUMERIC_TYPES
        else -> emptySet()
    }

    private fun QueryValueSchema.temporalRequirements(): List<Set<String>> = when (semanticType) {
        Temporal.Date -> listOf(DATE_TYPES)
        is Temporal.Epoch -> listOf(INTEGRAL_TYPES)
        else -> emptyList()
    }

    private fun QueryValueType.storageTypes(): Set<String> = when (this) {
        QueryValueType.STRING -> STRING_TYPES
        QueryValueType.BOOLEAN -> BOOLEAN_TYPES
        QueryValueType.INTEGER -> INTEGRAL_TYPES
        QueryValueType.DECIMAL -> NUMERIC_TYPES
        else -> emptySet()
    }

    companion object {
        /** The capabilities MongoDB evaluates per field. */
        private val CAPABILITIES = QueryCapability.entries - setOf(QueryCapability.FULL_TEXT_TERMS, QueryCapability.FULL_TEXT_PHRASE)

        private val DATE_OPERAND_CAPABILITIES = setOf(QueryCapability.EXACT_MATCH, QueryCapability.RANGE)

        private val LEGACY_DATE_OPERAND_CAPABILITIES = DATE_OPERAND_CAPABILITIES + setOf(
            QueryCapability.LITERAL_MATCH,
            QueryCapability.AGGREGATE_NUMERIC,
        )

        private val SEMANTICS: List<QuerySemanticType?> = listOf(
            null,
            Temporal.Date,
            Temporal.Epoch(TimeUnit.MILLISECONDS),
            Temporal.Formatted("yyyy-MM-dd"),
            NumericFormat.Decimal(2),
        )

        private val TYPES = listOf(
            QueryValueType.STRING,
            QueryValueType.INTEGER,
            QueryValueType.DECIMAL,
            QueryValueType.BOOLEAN,
            QueryValueType("BSON"),
        )

        private val VALUE_TYPE_SETS: List<Set<QueryValueType>> = (1 until (1 shl TYPES.size)).map { mask ->
            TYPES.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        }
    }
}
