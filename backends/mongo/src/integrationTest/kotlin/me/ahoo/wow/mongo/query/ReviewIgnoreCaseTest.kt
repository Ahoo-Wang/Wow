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

package me.ahoo.wow.mongo.query

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.EndsWithFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.QueryModelSchema
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.util.UUID

/**
 * Deep review r1 (the query path): ignoring case compares the lower case of the field's text with the lower case of
 * the value, by Unicode's lowercase mapping (`reference/query-language`), and the reference page says MongoDB answers
 * as the in-memory reference does. MongoDB's `\$regex` with the `i` option folds case by Unicode's case folding
 * instead, where a letter has more than one other case: `s` meets `ſ` (U+017F), `σ` meets `ς`, `μ` meets the micro
 * sign `µ` (U+00B5), none of which lowers to the other. Each case fails while MongoDB matches more than the reference.
 */
internal class ReviewIgnoreCaseTest {
    private val type = AggregateType("review_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")

    private class Side(val backend: QueryBackend, val schema: QueryModelSchema) {
        suspend fun ids(filter: FilterExpression, sort: List<Sort> = listOf(Sort(ID, Sort.Direction.ASC))) =
            backend.list(QueryAdmission.Trusted.list(ListQuery(filter, sort = sort, limit = 100), schema)).toList()
                .map { it.path("aggregateId").stringValue() }
    }

    private val records = listOf(
        "e" to """{"name":"ſ","status":"Z"}""",
        "g" to """{"name":"λόγος","status":"Z"}""",
        "h" to """{"name":"5µm","status":"Z"}""",
    )

    private val sides: Pair<Side, Side> by lazy {
        runBlocking {
            val orders = records.map { (id, state) -> QueryFixtures.snapshot(type, id, state) }
            val store = MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)
            orders.forEach { store.save(it) }
            val mongo = MongoSnapshotQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)
                .binding(type)!!
            val memory = InMemoryQueryStorage().also { storage -> orders.forEach(storage::save) }.binding(type)
            Side(mongo.backend, QueryFixtures.schema(type, mongo.storage)) to
                Side(memory.backend, QueryFixtures.schema(type, memory.storage))
        }
    }

    private fun same(name: String, run: suspend Side.() -> Any?): DynamicTest = DynamicTest.dynamicTest(name) {
        runBlocking {
            val (mongo, memory) = sides
            val reference = memory.run()
            mongo.run().assert().describedAs("$name: mongo against the in-memory reference ($reference)")
                .isEqualTo(reference)
        }
    }

    @TestFactory
    fun probes(): List<DynamicTest> = listOf(
        same("contains s ignoring case") { ids(ContainsFilter(NAME, "s", StringComparison.CASE_INSENSITIVE)) },
        same("starts with s ignoring case") { ids(StartsWithFilter(NAME, "s", StringComparison.CASE_INSENSITIVE)) },
        same("contains sigma ignoring case") { ids(ContainsFilter(NAME, "σ", StringComparison.CASE_INSENSITIVE)) },
        same("ends with sigma ignoring case") { ids(EndsWithFilter(NAME, "οσ", StringComparison.CASE_INSENSITIVE)) },
        same("contains mu ignoring case") { ids(ContainsFilter(NAME, "μ", StringComparison.CASE_INSENSITIVE)) },
    )

    private companion object {
        val ID = QueryField("aggregateId")
        val NAME = QueryField("state.name")
    }
}
