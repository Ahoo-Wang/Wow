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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.QueryStorageFamily.BOOLEAN
import me.ahoo.wow.query.schema.QueryStorageFamily.DATE
import me.ahoo.wow.query.schema.QueryStorageFamily.EXACT_STRING
import me.ahoo.wow.query.schema.QueryStorageFamily.INTEGRAL
import me.ahoo.wow.query.schema.QueryStorageFamily.NUMERIC
import me.ahoo.wow.query.schema.QueryStorageFamily.SIGNED_INTEGRAL
import me.ahoo.wow.query.schema.QueryStorageFamily.STRING
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class QueryStorageFamiliesTest {
    private val lenient = QueryStorageFamilyRules(dateOperands = false, strictValueTypes = false)

    @Test
    fun `plain values need the family of each declared type`() {
        val mixed =
            scalar(QueryValueType.STRING, QueryValueType.INTEGER, QueryValueType.DECIMAL, QueryValueType.BOOLEAN)
        mixed.storageFamilies(QueryCapability.EXACT_MATCH).assert()
            .isEqualTo(listOf(setOf(STRING), setOf(INTEGRAL), setOf(NUMERIC), setOf(BOOLEAN)))
        scalar(
            QueryValueType.STRING
        ).storageFamilies(QueryCapability.LITERAL_MATCH).assert().isEqualTo(listOf(setOf(STRING)))
        scalar(
            QueryValueType.INTEGER
        ).storageFamilies(QueryCapability.LITERAL_MATCH).assert().isEqualTo(listOf(emptySet<QueryStorageFamily>()))
        scalar(
            QueryValueType.INTEGER,
            QueryValueType.DECIMAL
        ).storageFamilies(QueryCapability.AGGREGATE_NUMERIC).assert()
            .isEqualTo(listOf(setOf(INTEGRAL), setOf(NUMERIC)))
        scalar(QueryValueType.STRING).storageFamilies(QueryCapability.AGGREGATE_TEMPORAL).assert().isEmpty()
        QueryCapability.entries.filter { it == QueryCapability.PRESENCE || it == QueryCapability.ELEMENT_SCOPE }.forEach {
            mixed.storageFamilies(it).assert().isEmpty()
        }
    }

    @Test
    fun `instants need a native date or a signed integral and never a literal match`() {
        val date = scalar(QueryValueType.STRING, semantic = Temporal.Date)
        val epoch = scalar(QueryValueType.INTEGER, semantic = Temporal.Epoch(TimeUnit.MILLISECONDS))
        listOf(
            QueryCapability.EXACT_MATCH,
            QueryCapability.RANGE,
            QueryCapability.SORT,
            QueryCapability.AGGREGATE_TEMPORAL
        )
            .forEach { capability ->
                date.storageFamilies(capability).assert().isEqualTo(listOf(setOf(DATE)))
                epoch.storageFamilies(capability).assert().isEqualTo(listOf(setOf(SIGNED_INTEGRAL)))
            }
        date.storageFamilies(QueryCapability.LITERAL_MATCH).assert().isEmpty()
        epoch.storageFamilies(QueryCapability.LITERAL_MATCH).assert().isEmpty()
        date.storageFamilies(QueryCapability.AGGREGATE_NUMERIC).assert().isEmpty()
        epoch.storageFamilies(QueryCapability.AGGREGATE_NUMERIC).assert().isEqualTo(listOf(setOf(SIGNED_INTEGRAL)))
    }

    @Test
    fun `a formatted date ranges over exact strings`() {
        scalar(QueryValueType.STRING, semantic = Temporal.Formatted("yyyy-MM-dd"))
            .storageFamilies(QueryCapability.RANGE).assert().isEqualTo(listOf(setOf(EXACT_STRING)))
    }

    @Test
    fun `strict value types reject what a lenient storage reads per declared type`() {
        val mixedRange = scalar(QueryValueType.STRING, QueryValueType.INTEGER)
        mixedRange.storageFamilies(QueryCapability.RANGE).assert().isEmpty()
        mixedRange.storageFamilies(
            QueryCapability.RANGE,
            lenient
        ).assert().isEqualTo(listOf(setOf(STRING), setOf(INTEGRAL)))

        val numericDate = scalar(QueryValueType.INTEGER, semantic = Temporal.Date)
        numericDate.storageFamilies(QueryCapability.SORT).assert().isEmpty()
        numericDate.storageFamilies(QueryCapability.SORT, lenient).assert().isEqualTo(listOf(setOf(DATE)))

        val decimalEpoch = scalar(QueryValueType.DECIMAL, semantic = Temporal.Epoch(TimeUnit.SECONDS))
        decimalEpoch.storageFamilies(QueryCapability.AGGREGATE_NUMERIC).assert().isEmpty()
        decimalEpoch.storageFamilies(
            QueryCapability.AGGREGATE_NUMERIC,
            lenient
        ).assert().isEqualTo(listOf(setOf(NUMERIC)))
    }

    @Test
    fun `without date operands a date only sorts, groups and aggregates by time`() {
        val date = scalar(QueryValueType.STRING, semantic = Temporal.Date)
        val rules = QueryStorageFamilyRules(dateOperands = false)
        listOf(
            QueryCapability.EXACT_MATCH,
            QueryCapability.LITERAL_MATCH,
            QueryCapability.RANGE,
            QueryCapability.AGGREGATE_NUMERIC,
        ).forEach { date.storageFamilies(it, rules).assert().isEmpty() }
        listOf(QueryCapability.SORT, QueryCapability.AGGREGATE_TERMS, QueryCapability.AGGREGATE_TEMPORAL).forEach {
            date.storageFamilies(it, rules).assert().isEqualTo(listOf(setOf(DATE)))
        }
    }

    private fun scalar(vararg types: QueryValueType, semantic: QuerySemanticType? = null) =
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = types.toSet(), semanticType = semantic)
}
