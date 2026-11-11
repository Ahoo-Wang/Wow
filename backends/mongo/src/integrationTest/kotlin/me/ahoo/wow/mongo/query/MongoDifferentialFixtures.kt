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
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.cursor
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.QueryModelSchema
import org.junit.jupiter.api.DynamicTest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * What the differential tests share: a fresh aggregate type per test, its records saved to MongoDB and to the
 * in-memory reference, each [Side]'s reads, and [same], which runs one on both sides and compares the answers.
 */
internal abstract class MongoDifferentialFixtures {
    private val type = AggregateType("diff_" + UUID.randomUUID().toString().replace("-", "").take(12), "order")

    protected class Side(val backend: QueryBackend, val schema: QueryModelSchema) {
        suspend fun ids(filter: FilterExpression, sort: List<Sort> = emptyList()): List<String> =
            backend.list(QueryAdmission.Trusted.list(ListQuery(filter, sort = sort, limit = 100), schema)).toList()
                .map { it.path("aggregateId").stringValue() }

        suspend fun rows(query: ListQuery): List<ObjectNode> =
            backend.list(QueryAdmission.Trusted.list(query, schema)).toList()

        suspend fun aggregate(query: AggregationQuery): List<Map<String, Any?>> =
            backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema)).toList().map { it.toMap() }

        suspend fun cursorIds(sort: List<Sort>, size: Int, filter: FilterExpression = MatchAllFilter): List<List<String>> {
            val pages = mutableListOf<List<String>>()
            var token: String? = null
            do {
                val page = backend.cursor(
                    QueryAdmission.Trusted.cursor(
                        CursorQuery(filter, sort = sort, size = size, cursor = token),
                        schema
                    ),
                )
                pages += page.list.map { it.path("aggregateId").stringValue() }
                token = page.nextCursor
            } while (token != null && pages.size < 20)
            return pages
        }
    }

    private val sides: Pair<Side, Side> by lazy {
        runBlocking {
            val orders = QueryFixtures.orders(type)
            val store = MongoSnapshotStore(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)
            orders.forEach { store.save(it) }
            val mongo = MongoSnapshotQueryStorage(MongoLogFixture.client, MongoLogFixture.DATABASE, type.context)
                .binding(type)
            val memory = InMemoryQueryStorage().also { storage -> orders.forEach(storage::save) }.binding(type)
            Side(mongo!!.backend, QueryFixtures.schema(type, mongo!!.storage)) to
                Side(memory.backend, QueryFixtures.schema(type, memory.storage))
        }
    }

    protected fun <T> same(name: String, run: suspend Side.() -> T): DynamicTest = DynamicTest.dynamicTest(name) {
        runBlocking {
            val (mongo, memory) = sides
            mongo.run().assert().describedAs(name).isEqualTo(memory.run())
        }
    }

    protected companion object {
        fun field(name: String) = QueryField("state.$name")
        val NAME = field("name")
        val STATUS = field("status")
        val AMOUNT = field("amount")
        val QUANTITY = field("quantity")
        val KEYWORDS = field("keywords")
        val CREATED = field("createdAt")
        val ACTIVE = DeletionFilter(DeletionState.ACTIVE)

        private fun JsonNode.normalized(): Any? = when {
            isObject -> properties().associate { (name, value) -> name to value.normalized() }
            isArray -> toList().map { it.normalized() }
            isNumber -> BigDecimal(doubleValue()).setScale(6, RoundingMode.HALF_UP)
            isString -> stringValue()
            isBoolean -> booleanValue()
            else -> null
        }

        fun ObjectNode.toMap(): Map<String, Any?> = normalized().let {
            @Suppress("UNCHECKED_CAST")
            it as Map<String, Any?>
        }

        fun QueryField<*>.child(): QueryField<*> = QueryField(path.substringAfterLast('.'))

        fun numeric(function: AggregationFunction, alias: String, field: QueryField<*> = AMOUNT) =
            AggregationMetric.Numeric(function, AggregationExpression.Field(field), alias)
    }
}
