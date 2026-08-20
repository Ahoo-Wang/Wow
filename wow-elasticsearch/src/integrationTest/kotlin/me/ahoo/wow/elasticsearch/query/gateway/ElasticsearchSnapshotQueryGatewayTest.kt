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

package me.ahoo.wow.elasticsearch.query.gateway

import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.PredicateExpression
import me.ahoo.wow.api.query.PredicateOperator
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QuerySort
import me.ahoo.wow.api.query.QuerySortDirection
import me.ahoo.wow.api.query.SearchExpression
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.initSnapshotTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStore
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.query.backend.QueryRouter
import me.ahoo.wow.query.gateway.SnapshotQueryGateway
import me.ahoo.wow.query.gateway.SnapshotQueryGatewayFactory
import me.ahoo.wow.query.schema.JacksonQuerySchemaProvider
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QuerySchema
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.kotlin.test.test
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ElasticsearchSnapshotQueryGatewayTest {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()

    private lateinit var client: ReactiveElasticsearchClient
    private lateinit var gateway: SnapshotQueryGateway<MockStateAggregate>
    private lateinit var snapshotStore: ElasticsearchSnapshotStore
    private lateinit var backend: ElasticsearchSnapshotQueryBackend
    private lateinit var aggregateId: String

    @BeforeEach
    fun setup() {
        client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch)
        client.initSnapshotTemplate()
        val id = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId())
        val stateAggregate = ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, id)
        snapshotStore = ElasticsearchSnapshotStore(client)
        snapshotStore
            .save(SimpleSnapshot(stateAggregate, Clock.systemUTC().millis()))
            .test()
            .verifyComplete()
        aggregateId = id.id
        backend = ElasticsearchSnapshotQueryBackend(client, ElasticsearchQueryBackendOptions(pitPageSize = 1))
        gateway = SnapshotQueryGatewayFactory.create(
            schemaProvider = JacksonQuerySchemaProvider(JsonSerializer),
            router = QueryRouter { backend },
            objectMapper = JsonSerializer
        ).create(MOCK_AGGREGATE_METADATA)
    }

    @Test
    fun `should query snapshot through PIT and materialize typed result`() {
        val query = aggregateQuery()

        gateway.firstRecord(query)
            .test()
            .assertNext { record -> check(record["aggregateId"].asString() == aggregateId) }
            .verifyComplete()

        gateway.first(query)
            .test()
            .assertNext { snapshot -> check(snapshot.aggregateId == aggregateId) }
            .verifyComplete()
    }

    @Test
    fun `should return exact page total and count`() {
        val query = aggregateQuery()

        gateway.pageRecords(query, page = 1, size = 10)
            .test()
            .assertNext { page ->
                check(page.total == 1L)
                check(page.items.single()["aggregateId"].asString() == aggregateId)
            }
            .verifyComplete()

        gateway.count(query.filter)
            .test()
            .expectNext(1L)
            .verifyComplete()
    }

    @Test
    fun `should use full text mapping and fail closed for an unsafe exact subfield`() {
        gateway.streamRecords(Query(filter = SearchExpression("missing", setOf(LogicalField("state.data")))))
            .test()
            .verifyComplete()

        val exact = Query(
            filter = PredicateExpression(
                LogicalField("state.data"),
                PredicateOperator.EQ,
                listOf(JsonNodeFactory.instance.textNode("missing"))
            )
        )
        gateway.firstRecord(exact)
            .test()
            .expectErrorMatches { error ->
                error is QueryException && error.code == QueryErrorCode.BACKEND_NOT_READY
            }
            .verify()
    }

    @Test
    fun `should query an unmapped field with safe non-null semantics`() {
        val optional = LogicalField("state.optional")
        val fields = LinkedHashMap(JacksonQuerySchemaProvider(JsonSerializer).getSchema(MOCK_AGGREGATE_METADATA).fields)
        fields[optional] = QueryFieldSchema(optional, QueryValueKind.STRING, nullable = true)
        val legacyGateway = SnapshotQueryGatewayFactory.create(
            schemaProvider = { QuerySchema(fields) },
            router = QueryRouter { backend },
            objectMapper = JsonSerializer
        ).create(MOCK_AGGREGATE_METADATA)

        legacyGateway.firstRecord(
            Query(
                filter = PredicateExpression(
                    optional,
                    PredicateOperator.IS_NOT_NULL
                )
            )
        ).test()
            .verifyComplete()
    }

    @Test
    fun `should reject presence-sensitive queries without metadata`() {
        val query = Query(
            filter = PredicateExpression(
                LogicalField("state.data"),
                PredicateOperator.IS_NULL
            )
        )

        gateway.firstRecord(query).test()
            .expectErrorMatches { error ->
                error is QueryException && error.code == QueryErrorCode.UNSUPPORTED_QUERY
            }
            .verify()
    }

    @Test
    fun `should close PIT when downstream cancels`() {
        val secondId = MOCK_AGGREGATE_METADATA.aggregateId(generateGlobalId())
        val secondState = ConstructorStateAggregateFactory.create(MOCK_AGGREGATE_METADATA.state, secondId)
        snapshotStore.save(SimpleSnapshot(secondState, Clock.systemUTC().millis())).test().verifyComplete()
        var opened: String? = null
        var closed: String? = null
        val closedLatch = CountDownLatch(1)
        backend.onPitOpened = { opened = it }
        backend.onPitClosed = {
            closed = it
            closedLatch.countDown()
        }

        gateway.streamRecords(Query())
            .test(1)
            .expectNextCount(1)
            .thenCancel()
            .verify()

        check(opened != null)
        check(closedLatch.await(5, TimeUnit.SECONDS))
        check(closed == opened)
    }

    @Test
    fun `should close PIT on complete and error`() {
        verifyPitClosed {
            gateway.firstRecord(aggregateQuery()).test().expectNextCount(1).verifyComplete()
        }

        verifyPitClosed {
            backend.beforePitSearch = { error("search failed") }
            gateway.firstRecord(aggregateQuery())
                .test()
                .expectErrorMatches { error ->
                    error is QueryException && error.code == QueryErrorCode.BACKEND_FAILURE
                }
                .verify()
        }
    }

    private fun verifyPitClosed(block: () -> Unit) {
        var opened: String? = null
        var closed: String? = null
        val closedLatch = CountDownLatch(1)
        backend.onPitOpened = { opened = it }
        backend.onPitClosed = {
            closed = it
            closedLatch.countDown()
        }
        block()
        check(opened != null)
        check(closedLatch.await(5, TimeUnit.SECONDS))
        check(closed == opened)
        backend.beforePitSearch = {}
    }

    private fun aggregateQuery(): Query = Query(
        filter = PredicateExpression(
            LogicalField("aggregateId"),
            PredicateOperator.EQ,
            listOf(JsonNodeFactory.instance.textNode(aggregateId))
        ),
        sort = listOf(QuerySort(LogicalField("aggregateId"), QuerySortDirection.ASC))
    )
}
