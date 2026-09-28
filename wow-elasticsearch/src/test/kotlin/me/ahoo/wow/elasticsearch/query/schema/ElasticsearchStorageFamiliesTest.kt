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
import me.ahoo.wow.query.schema.QueryStorageFamily
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.storageFamilies
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The Catalog's strict capability table ([storageFamilies]) reproduces the adapter's own table for every semantic
 * type, declared value types and capability. Both read a requirement list with an empty alternative as unsupported,
 * like an empty list, so the comparison folds the two. Wave 4 moves [kinds] into the adapter and deletes its table.
 */
class ElasticsearchStorageFamiliesTest {
    @Test
    fun `the catalog table reproduces the adapter table`() {
        SEMANTICS.forEach { semantic ->
            VALUE_TYPE_SETS.forEach { valueTypes ->
                val value = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = valueTypes, semanticType = semantic)
                QueryCapability.entries.forEach { capability ->
                    val table = value.storageFamilies(
                        capability
                    ).map { families -> families.flatMap { it.kinds() }.toSet() }
                    table.supported().assert().describedAs("$semantic $valueTypes $capability")
                        .isEqualTo(value.storageRequirements(capability).supported())
                }
            }
        }
    }

    private fun List<Set<Property.Kind>>.supported(): List<Set<Property.Kind>> =
        takeUnless { requirements -> requirements.any { it.isEmpty() } }.orEmpty()

    private fun QueryStorageFamily.kinds(): Set<Property.Kind> = when (this) {
        QueryStorageFamily.STRING -> STRING_KINDS
        QueryStorageFamily.EXACT_STRING -> KEYWORD_KINDS
        QueryStorageFamily.INTEGRAL -> INTEGER_KINDS
        QueryStorageFamily.SIGNED_INTEGRAL -> SIGNED_INTEGER_KINDS
        QueryStorageFamily.NUMERIC -> NUMERIC_KINDS
        QueryStorageFamily.BOOLEAN -> BOOLEAN_KINDS
        QueryStorageFamily.DATE -> DATE_KINDS
    }

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
