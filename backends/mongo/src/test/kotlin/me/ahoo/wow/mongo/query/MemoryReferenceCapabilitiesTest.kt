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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
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
 * The in-memory reference's snapshot model, which a `memoryQueries` spec and a node on the memory store read, against
 * MongoDB's: the query TCK's order state ([QueryBackendSpec.DECLARATION]) compiled once over the reference's facts and
 * once over [MongoQuerySchemaAdapter]'s facts of a collection with no index and no validator, as before the
 * aggregate's first snapshot. The reference grants what MongoDB grants and nothing it refuses (#6968); what it grants
 * beyond PostgreSQL is held by the PostgreSQL module's test of the same name.
 */
class MemoryReferenceCapabilitiesTest {
    private val type = AggregateType("reference", "order")

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
    fun `the memory reference should grant a field exactly what MongoDB grants it`() {
        // A date string only exists on MongoDB, which stores it as text (MongoQuerySchemaAdapter.RULES): so on memory.
        val extra = paths(memorySchema).associateWith { path ->
            capabilities(memorySchema, path) - capabilities(mongoSchema, path)
        }.filterValues { it.isNotEmpty() }
        extra.assert().describedAs("what memory grants and MongoDB does not, by field").isEmpty()
        val missing = paths(mongoSchema).associateWith { path ->
            capabilities(mongoSchema, path) - capabilities(memorySchema, path)
        }.filterValues { it.isNotEmpty() }
        missing.assert().describedAs("what MongoDB grants and memory does not, by field").isEmpty()
    }

    @Test
    fun `a sort by two independent arrays should be refused on the memory reference as MongoDB refuses it`() {
        val keywords = QueryField("state.keywords")
        val tags = QueryField("state.probeTags")
        val query = ListQuery(sort = listOf(Sort(keywords, Sort.Direction.ASC), Sort(tags, Sort.Direction.ASC)))
        val refused = QueryViolation.ParallelArraySort(tags, keywords)
        refusalOf { QueryAdmission.Trusted.list(query, mongoSchema) }.assert().isEqualTo(refused)
        refusalOf { QueryAdmission.Trusted.list(query, memorySchema) }.assert().isEqualTo(refused)
        // One array alone sorts on both.
        val one = ListQuery(sort = listOf(Sort(keywords, Sort.Direction.ASC)))
        QueryAdmission.Trusted.list(one, memorySchema).assert().isNotNull()
        QueryAdmission.Trusted.list(one, mongoSchema).assert().isNotNull()
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
