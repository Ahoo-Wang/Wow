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

package me.ahoo.wow.mongo.query.review

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.mongo.query.MongoQuerySchemaAdapter
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.tck.QueryBackendSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Deep review round 1 of the test kit: where the in-memory query storage, which `memoryQueries` and a node on the
 * memory store read, admits what MongoDB refuses. The schema is the query TCK's order state
 * ([QueryBackendSpec.DECLARATION]), compiled once over the memory storage's facts and once over
 * [MongoQuerySchemaAdapter]'s facts of a collection with no index and no validator, as before the aggregate's first
 * snapshot (MongoQuerySchemaAdapter.kt:68). Each case asserts what MongoDB does, so it fails on memory until the two
 * agree.
 */
class MemoryQueryReviewR1Test {
    private val type = AggregateType("review", "order")

    private val memorySchema: QueryModelSchema by lazy { schemaOver(InMemoryQueryStorage().binding(type).storage) }
    private val mongoSchema: QueryModelSchema by lazy {
        schemaOver(
            object : QueryStorageAdapter {
                override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
                    MongoQuerySchemaAdapter.facts(logicalSchema, indexes = emptyList(), validatorSchema = null)
            },
        )
    }

    @Test
    fun `every capability memory grants a field should be one MongoDB grants it`() {
        // Memory grants what the strict family table allows (query/query/.../memory/InMemoryQueryBackend.kt:284-296,
        // `dateOperands` on). MongoDB stores a date string as text and leaves it PRESENCE alone (`dateOperands =
        // false`, backends/mongo/.../query/MongoQuerySchemaAdapter.kt:99, and `semanticType == Temporal.Date`
        // refuses every other capability, :229).
        val extra = paths(memorySchema).associateWith { path ->
            capabilities(memorySchema, path) - capabilities(mongoSchema, path)
        }.filterValues { it.isNotEmpty() }
        extra.assert().describedAs("what memory grants and MongoDB does not, by field").isEmpty()
    }

    @Test
    fun `a sort by two independent arrays should be refused on memory as MongoDB refuses it`() {
        // MongoDB cannot sort by parallel arrays (`parallelArraySort = NONE`, MongoQuerySchemaAdapter.kt:89), so
        // admission refuses it (query/query/.../resolve/QueryResolver.kt:518-519, :540-548). Memory leaves
        // `parallelArraySort` at its NATIVE default (InMemoryQueryBackend.kt:311-317) and admits it.
        val keywords = QueryField("state.keywords")
        val tags = QueryField("state.probeTags")
        val query = ListQuery(sort = listOf(Sort(keywords, Sort.Direction.ASC), Sort(tags, Sort.Direction.ASC)))
        val refused = QueryViolation.ParallelArraySort(tags, keywords)
        refusalOf { QueryAdmission.Trusted.list(query, mongoSchema) }.assert().isEqualTo(refused)
        refusalOf { QueryAdmission.Trusted.list(query, memorySchema) }.assert().isEqualTo(refused)
    }

    private fun schemaOver(adapter: QueryStorageAdapter): QueryModelSchema {
        val context = QuerySchemaContext(type, QueryModel.SNAPSHOT)
        val source = BeanQuerySchemaSource(listOf(QuerySchemaRegistration(context, QueryBackendSpec.DECLARATION)))
        return runBlocking { DefaultQueryModelSchemaProvider(context, listOf(source), adapter).schema() }
    }

    private companion object {
        /** The logical path of each field the schema binds that has no map key, as a query names it. */
        fun paths(schema: QueryModelSchema): List<String> = schema.bindings.keys
            .filter { path -> path.segments.isNotEmpty() && path.segments.none { it is QueryPathSegment.Key } }
            .map { path -> path.segments.filterIsInstance<QueryPathSegment.Property>().joinToString(".") { it.name } }
            .distinct()

        fun capabilities(schema: QueryModelSchema, path: String): Set<QueryCapability> =
            schema.field(QueryField(path))?.capabilities.orEmpty()

        fun refusalOf(admit: () -> Any): QueryViolation? =
            assertThrows<QuerySchemaValidationException> { admit() }.violation
    }
}
