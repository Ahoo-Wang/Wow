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

package me.ahoo.wow.elasticsearch.query

import co.elastic.clients.elasticsearch.indices.ExistsRequest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initEventStreamTemplate
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryBackendFactory
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryBackendFactory
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.aggregate
import me.ahoo.wow.query.cursor
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.dsl.filterExpression
import me.ahoo.wow.query.list
import me.ahoo.wow.query.paged
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSource
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.query.QueryTarget
import me.ahoo.wow.tck.query.target
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.kotlin.test.test
import java.time.Clock

/**
 * An aggregate's index does not exist until its first write: every read before it answers nothing, with the schema
 * its templates will give the index, rather than failing as an unavailable query schema.
 */
class ElasticsearchMissingIndexQueryTest {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    private lateinit var client: ReactiveElasticsearchClient

    @BeforeEach
    fun setup() {
        client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
    }

    private fun snapshots(): QueryTarget<QueryBackend> =
        ElasticsearchSnapshotQueryBackendFactory(client).target(MOCK_AGGREGATE_METADATA, listOf(STATE_FIELDS))

    private fun indexExists(name: String): Boolean =
        client.indices().exists(ExistsRequest.of { it.index(name) }).block()!!.value()

    @Test
    fun `a snapshot index before the first write reads as empty, with the template's mapping`() {
        client.initSnapshotTemplate()
        val target = snapshots()
        val schema = target.schemaProvider.schema().block()!!
        indexExists(MOCK_AGGREGATE_METADATA.toSnapshotIndexName()).assert().isFalse()

        // The template maps the system fields: a filter and a sort on them are admitted.
        schema.field(QueryField("tenantId"))?.binding(QueryCapability.EXACT_MATCH).assert().isNotNull()
        val filter = filterExpression { tenantId("tenant") }
        val sort = listOf(Sort(QueryField("version"), Sort.Direction.DESC))
        target.backend.list(QueryAdmission.Trusted.list(ListQuery(filter, sort = sort, limit = 10), schema))
            .test().verifyComplete()
        target.backend.list(QueryAdmission.Trusted.list(ListQuery(filter, sort = sort), schema))
            .test().verifyComplete()
        target.backend.paged(QueryAdmission.Trusted.paged(PagedQuery(filter, sort = sort), schema))
            .test()
            .assertNext { page ->
                page.total.assert().isZero()
                page.list.assert().isEmpty()
            }.verifyComplete()
        target.backend.cursor(
            QueryAdmission.Trusted.cursor(
                CursorQuery(filter, sort = listOf(Sort(QueryField("aggregateId"), Sort.Direction.ASC))),
                schema
            )
        ).test().assertNext { page -> page.list.assert().isEmpty() }.verifyComplete()
        target.backend.count(QueryAdmission.Trusted.count(filter, schema)).test().expectNext(0L).verifyComplete()
        target.aggregate(schema, aggregation { count("count") })
            .test()
            .assertNext { row -> row.path("count").asLong().assert().isZero() }
            .verifyComplete()
        target.aggregate(schema, aggregation { terms("tenantId", "tenant"); count("count") })
            .test().verifyComplete()
        // A state field the templates do not map yet (dynamic mapping adds it on the first write) answers nothing.
        schema.provisional.assert().isTrue()
        val stateFilter = filterExpression { "state.createdAt" gte 0 }
        target.backend.count(QueryAdmission.Trusted.count(stateFilter, schema)).test().expectNext(0L).verifyComplete()
        target.backend.list(
            QueryAdmission.Trusted.list(
                ListQuery(stateFilter, sort = listOf(Sort(QueryField("state.data"), Sort.Direction.ASC)), limit = 10),
                schema
            )
        ).test().verifyComplete()
        indexExists(MOCK_AGGREGATE_METADATA.toSnapshotIndexName()).assert().isFalse()

        // The provisional schema is kept briefly, so queries of a never-written index do not each simulate it.
        target.schemaProvider.schema().block()!!.assert().isSameAs(schema)

        // The first write creates the index; once the provisional schema expires, the next load reads the index's
        // own mapping, with no refresh.
        val aggregateId = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId(), tenantId = "tenant")
        val state = ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, aggregateId)
        ElasticsearchSnapshotStore(client).save(SimpleSnapshot(state, Clock.systemUTC().millis())).block()
        // A query admitted against the provisional schema that finds the index is refused, not run on guesses.
        target.backend.count(QueryAdmission.Trusted.count(stateFilter, schema)).test()
            .expectError(QuerySchemaUnavailableException::class.java).verify()
        target.backend.list(QueryAdmission.Trusted.list(ListQuery(stateFilter, limit = 10), schema)).test()
            .expectError(QuerySchemaUnavailableException::class.java).verify()
        val deadline = System.nanoTime() + PROVISIONAL_WAIT.toNanos()
        var existing = target.schemaProvider.schema().block()!!
        while (existing.provisional && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
            existing = target.schemaProvider.schema().block()!!
        }
        existing.provisional.assert().isFalse()
        target.backend.count(QueryAdmission.Trusted.count(filter, existing)).test().expectNext(1L).verifyComplete()
        target.backend.count(QueryAdmission.Trusted.count(stateFilter, existing)).test().expectNext(1L).verifyComplete()
        target.schemaProvider.schema().block()!!.assert().isSameAs(existing)
    }

    @Test
    fun `a missing index that no template matches reads as empty`() {
        val target = snapshots()
        val schema = target.schemaProvider.schema().block()!!

        target.backend.count(QueryAdmission.Trusted.count(MatchAllFilter, schema))
            .test().expectNext(0L).verifyComplete()
        target.backend.list(QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, limit = 10), schema))
            .test().verifyComplete()
        // No field is mapped: system fields, state fields and ABAC tags all answer nothing rather than being refused.
        val access = with(me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy) {
            mapOf("department" to listOf("eng")).toFilterExpression()
        }
        listOf(filterExpression { tenantId("tenant") }, filterExpression { "state.data" eq "x" }, access)
            .forEach { filter ->
                target.backend.count(QueryAdmission.Trusted.count(filter, schema)).test().expectNext(0L).verifyComplete()
            }
    }

    @Test
    fun `an event stream index before the first write reads as empty`() {
        client.initEventStreamTemplate()
        val target = ElasticsearchEventStreamQueryBackendFactory(client).target(MOCK_AGGREGATE_METADATA)
        val schema = target.schemaProvider.schema().block()!!
        val filter = filterExpression { aggregateId("missing") }

        target.backend.list(QueryAdmission.Trusted.list(ListQuery(filter, limit = 10), schema))
            .test().verifyComplete()
        target.backend.count(QueryAdmission.Trusted.count(filter, schema)).test().expectNext(0L).verifyComplete()
        indexExists(MOCK_AGGREGATE_METADATA.toEventStreamIndexName()).assert().isFalse()
    }

    companion object {
        private val PROVISIONAL_WAIT =
            DefaultQueryModelSchemaProvider.DEFAULT_PROVISIONAL_TTL.plusSeconds(3)
        private const val POLL_MILLIS = 100L

        /** Two state fields the snapshot template does not map: dynamic mapping adds them on the first write. */
        private val STATE_FIELDS = object : QuerySchemaSource {
            override val priority: Int = QuerySchemaSourcePriority.BEAN

            override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.just(
                QuerySchemaDeclaration(
                    mapOf(
                        QueryField("state.createdAt") to field(QueryValueType.INTEGER),
                        QueryField("state.data") to field(QueryValueType.STRING),
                    )
                )
            )

            private fun field(type: QueryValueType) =
                QueryFieldDeclaration(valueTypes = DeclarationValue.Set(setOf(type)))
        }
    }

    private fun QueryTarget<QueryBackend>.aggregate(schema: QueryModelSchema, query: AggregationQuery) =
        backend.aggregate(QueryAdmission.Trusted.aggregate(query, schema))
}
