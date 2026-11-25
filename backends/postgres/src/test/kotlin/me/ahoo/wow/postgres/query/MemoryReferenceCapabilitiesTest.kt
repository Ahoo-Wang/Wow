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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.BeanQuerySchemaSource
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaRegistration
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.tck.QueryBackendSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The in-memory reference's snapshot model, which a `memoryQueries` spec and a node on the memory store read, against
 * PostgreSQL's: the query TCK's order state ([QueryBackendSpec.DECLARATION]) compiled once over the reference's facts
 * and once over [PostgresSnapshotStorageAdapter]'s, the facts of the table this module creates (#6968). The reference
 * grants a capability when PostgreSQL or MongoDB grants it: beyond PostgreSQL, only a sort and a grouping by an array,
 * which MongoDB grants (the MongoDB module's test of the same name holds the rest).
 */
class MemoryReferenceCapabilitiesTest {
    private val type = AggregateType("reference", "order")

    private val memorySchema: QueryModelSchema by lazy { schemaOver(InMemoryQueryStorage().binding(type).storage) }
    private val postgresSchema: QueryModelSchema by lazy { schemaOver(PostgresSnapshotStorageAdapter) }

    @Test
    fun `the memory reference should grant a field what PostgreSQL grants, and beyond it sort and terms over an array`() {
        val extra = paths(memorySchema).associateWith { path ->
            capabilities(memorySchema, path) - capabilities(postgresSchema, path)
        }.filterValues { it.isNotEmpty() }
        val byArray = setOf(QueryCapability.SORT, QueryCapability.AGGREGATE_TERMS)
        extra.assert().describedAs("what memory grants and PostgreSQL does not, by field").isEqualTo(
            mapOf("access.teams" to byArray, "state.keywords" to byArray, "state.probeTags" to byArray),
        )
        val missing = paths(postgresSchema).associateWith { path ->
            capabilities(postgresSchema, path) - capabilities(memorySchema, path)
        }.filterValues { it.isNotEmpty() }
        missing.assert().describedAs("what PostgreSQL grants and memory does not, by field").isEmpty()
    }

    @Test
    fun `a range over a date string should be refused on the memory reference as PostgreSQL refuses it`() {
        // An `Instant` state property is a date string, which PostgreSQL stores as text in jsonb.
        val placedAt = QueryField("state.placedAt")
        val query = ListQuery(GreaterThanFilter(placedAt, "2026-01-01T00:00:00Z"))
        val refused = QueryViolation.UnsupportedCapability(placedAt, setOf(QueryCapability.RANGE))
        refusalOf { QueryAdmission.Trusted.list(query, postgresSchema) }.assert().isEqualTo(refused)
        refusalOf { QueryAdmission.Trusted.list(query, memorySchema) }.assert().isEqualTo(refused)
        val sorted = ListQuery(sort = listOf(Sort(placedAt, Sort.Direction.ASC)))
        val unsorted = QueryViolation.UnsupportedCapability(placedAt, setOf(QueryCapability.SORT))
        refusalOf { QueryAdmission.Trusted.list(sorted, memorySchema) }.assert().isEqualTo(unsorted)
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
