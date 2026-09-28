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
import me.ahoo.wow.query.schema.QueryStorageFamily
import me.ahoo.wow.query.schema.QueryStorageFamilyRules
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.storageFamilies
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * The Catalog's capability table ([storageFamilies]) under MongoDB's rules reproduces the adapter's own table exactly,
 * for every semantic type, declared value types and capability MongoDB evaluates. Wave 4 moves [bsonTypes] into the
 * adapter and deletes its table.
 */
class MongoStorageFamiliesTest {
    @Test
    fun `the catalog table reproduces the adapter table`() {
        val rules = QueryStorageFamilyRules(dateOperands = false, strictValueTypes = false)
        SEMANTICS.forEach { semantic ->
            VALUE_TYPE_SETS.forEach { valueTypes ->
                val value = QueryValueSchema(QueryValueKind.SCALAR, valueTypes = valueTypes, semanticType = semantic)
                CAPABILITIES.forEach { capability ->
                    val table = value.storageFamilies(
                        capability,
                        rules
                    ).map { families -> families.flatMap { it.bsonTypes() }.toSet() }
                    table.assert().describedAs("$semantic $valueTypes $capability")
                        .isEqualTo(value.storageRequirements(capability))
                }
            }
        }
    }

    private fun QueryStorageFamily.bsonTypes(): Set<String> = when (this) {
        QueryStorageFamily.STRING, QueryStorageFamily.EXACT_STRING -> STRING_TYPES
        QueryStorageFamily.INTEGRAL, QueryStorageFamily.SIGNED_INTEGRAL -> INTEGRAL_TYPES
        QueryStorageFamily.NUMERIC -> NUMERIC_TYPES
        QueryStorageFamily.BOOLEAN -> BOOLEAN_TYPES
        QueryStorageFamily.DATE -> DATE_TYPES
    }

    companion object {
        /** The capabilities MongoDB evaluates per field. */
        private val CAPABILITIES = QueryCapability.entries - setOf(QueryCapability.FULL_TEXT_TERMS, QueryCapability.FULL_TEXT_PHRASE)

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
