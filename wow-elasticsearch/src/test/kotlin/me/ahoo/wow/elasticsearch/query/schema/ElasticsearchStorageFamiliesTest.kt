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

package me.ahoo.wow.elasticsearch.query.schema

import co.elastic.clients.elasticsearch._types.mapping.Property
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.NumericFormat
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.storageFamilies
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The adapter's capability table, the Catalog's strict table ([storageFamilies]) mapped to field kinds
 * ([storageKinds]), reproduces the table the adapter kept before it moved to the Catalog ([legacyRequirements]), for
 * every semantic type, declared value types and capability. Both read a requirement list with an empty alternative as
 * unsupported, like an empty list, so the comparison folds the two.
 */
class ElasticsearchStorageFamiliesTest {
    @Test
    fun `the catalog table reproduces the adapter's former table`() {
        SEMANTICS.forEach { semantic ->
            VALUE_TYPE_SETS.forEach { valueTypes ->
                val value = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = valueTypes, semanticType = semantic)
                QueryCapability.entries.forEach { capability ->
                    value.storageKinds(capability).supported().assert()
                        .describedAs("$semantic $valueTypes $capability")
                        .isEqualTo(value.legacyRequirements(capability).supported())
                }
            }
        }
    }

    private fun List<Set<Property.Kind>>.supported(): List<Set<Property.Kind>> =
        takeUnless { requirements -> requirements.any { it.isEmpty() } }.orEmpty()

    companion object {
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
            QueryValueType("GEO"),
        )

        private val VALUE_TYPE_SETS: List<Set<QueryValueType>> = (1 until (1 shl TYPES.size)).map { mask ->
            TYPES.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        }
    }
}

/** The adapter's own table before it adopted [storageFamilies], frozen here as the reference. */
private fun QueryValueSchema.legacyRequirements(capability: QueryCapability): List<Set<Property.Kind>> =
    when (capability) {
        QueryCapability.EXACT_MATCH,
        QueryCapability.SORT,
        QueryCapability.CURSOR_SORT,
        QueryCapability.AGGREGATE_TERMS,
        -> legacyValueRequirements()
        QueryCapability.LITERAL_MATCH,
        QueryCapability.FULL_TEXT_TERMS,
        QueryCapability.FULL_TEXT_PHRASE,
        -> legacyStringRequirements()
        QueryCapability.RANGE -> when (semanticType) {
            is Temporal.Formatted -> if (valueTypes == setOf(QueryValueType.STRING)) {
                listOf(
                    KEYWORD_KINDS
                )
            } else {
                emptyList()
            }
            else -> legacyTemporalRequirements().ifEmpty {
                legacyNumericRequirements().ifEmpty { legacyStringRequirements() }
            }
        }
        QueryCapability.AGGREGATE_NUMERIC -> legacyNumericRequirements()
        QueryCapability.AGGREGATE_TEMPORAL -> legacyTemporalRequirements()
        else -> emptyList()
    }

private fun QueryValueSchema.legacyValueRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    Temporal.Date, is Temporal.Epoch -> legacyTemporalRequirements()
    else -> valueTypes.map {
        when (it) {
            QueryValueType.STRING -> STRING_KINDS
            QueryValueType.INTEGER -> INTEGER_KINDS
            QueryValueType.DECIMAL -> NUMERIC_KINDS
            QueryValueType.BOOLEAN -> BOOLEAN_KINDS
            else -> emptySet()
        }
    }
}

private fun QueryValueSchema.legacyStringRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    Temporal.Date, is Temporal.Epoch -> emptyList()
    else -> if (valueTypes == setOf(QueryValueType.STRING)) listOf(STRING_KINDS) else emptyList()
}

private fun QueryValueSchema.legacyNumericRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    Temporal.Date -> emptyList()
    is Temporal.Epoch -> legacyTemporalRequirements()
    else -> if (valueTypes.all { it == QueryValueType.INTEGER || it == QueryValueType.DECIMAL }) {
        valueTypes.map { if (it == QueryValueType.INTEGER) INTEGER_KINDS else NUMERIC_KINDS }
    } else {
        emptyList()
    }
}

private fun QueryValueSchema.legacyTemporalRequirements(): List<Set<Property.Kind>> = when (semanticType) {
    Temporal.Date -> if (valueTypes == setOf(QueryValueType.STRING)) listOf(DATE_KINDS) else emptyList()
    is Temporal.Epoch -> if (valueTypes == setOf(QueryValueType.INTEGER)) listOf(SIGNED_INTEGER_KINDS) else emptyList()
    else -> emptyList()
}
