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

package me.ahoo.wow.postgres.query

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.postgres.PostgresFixture
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.QueryModelSchema
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.util.UUID

/**
 * Deep review r1 (the query path): PostgreSQL (`COLLATE "C"`) orders text by code point, and so the in-memory reference must; it orders
 * by UTF-16 code unit instead (`InMemoryStringOrderTest` of `:query`). `Ａ` (U+FF21) sorts before `😀` (U+1F600)
 * by code point, after it by UTF-16 code unit. Each case fails while the reference disagrees with the store.
 */
internal class ReviewStringOrderTest {
    private val type = AggregateType("review_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")

    private class Side(val backend: QueryBackend, val schema: QueryModelSchema) {
        suspend fun ids(filter: FilterExpression, sort: List<Sort> = listOf(Sort(ID, Sort.Direction.ASC))) =
            backend.list(QueryAdmission.Trusted.list(ListQuery(filter, sort = sort, limit = 100), schema)).toList()
                .map { it.path("aggregateId").stringValue() }

        suspend fun aggregate(query: AggregationQuery) =
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList().map { it.toString() }
    }

    private val records = listOf(
        "a" to """{"name":"z","status":"W","quantity":1,"amount":0.1}""",
        "b" to """{"name":"Ａ","status":"X","quantity":9007199254740993,"amount":0.30}""",
        "c" to """{"name":"😀","status":"Y","quantity":2,"amount":2.5}""",
        "d" to """{"name":"","status":"Y","quantity":3}""",
    )

    private val sides: Pair<Side, Side> by lazy {
        runBlocking {
            val orders = records.map { (id, state) -> QueryFixtures.snapshot(type, id, state) }
            val store = PostgresFixture.snapshotStore(type.context)
            orders.forEach { store.save(it) }
            val postgres = PostgresSnapshotQueryStorage(PostgresFixture.dataSource, type.context).binding(type)!!
            val memory = InMemoryQueryStorage().also { storage -> orders.forEach(storage::save) }.binding(type)
            Side(postgres.backend, QueryFixtures.schema(type, postgres.storage)) to
                Side(memory.backend, QueryFixtures.schema(type, memory.storage))
        }
    }

    private fun same(name: String, run: suspend Side.() -> Any?): DynamicTest = DynamicTest.dynamicTest(name) {
        runBlocking {
            val (postgres, memory) = sides
            val reference = memory.run()
            postgres.run().assert().describedAs("$name: postgres against the in-memory reference ($reference)")
                .isEqualTo(reference)
        }
    }

    @TestFactory
    fun probes(): List<DynamicTest> = listOf(
        same("sort by name") { ids(MatchAllFilter, listOf(Sort(NAME, Sort.Direction.ASC))) },
        same("name after U+FF21") { ids(GreaterThanFilter(NAME, "\uFF21")) },
        same("statuses by their first name") {
            aggregate(
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.Terms(STATUS, "status")),
                    metrics = listOf(AggregationMetric.First(NAME, "first", orderBy = QueryField("aggregateId"))),
                    sort = listOf(Sort(QueryField("first"), Sort.Direction.DESC)),
                ),
            )
        },
    )

    private companion object {
        val ID = QueryField("aggregateId")
        val NAME = QueryField("state.name")
        val STATUS = QueryField("state.status")
    }
}
