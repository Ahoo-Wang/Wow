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

package me.ahoo.wow.elasticsearch.query.backend

import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.mapping.Property
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping
import co.elastic.clients.elasticsearch.core.IndexRequest
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.json.JsonData
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.ElasticsearchSearchResponseGate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryService
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.policy.QueryFieldAccess
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.query.backend.ObservableQueryBackendFactory
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.QueryBackendClientHold
import me.ahoo.wow.tck.query.backend.SnapshotQueryBackendSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class ElasticsearchSnapshotQueryBackendSpec : SnapshotQueryBackendSpec() {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()
    private lateinit var fixture: ElasticsearchPortableQueryBackendFixture
    private lateinit var client: org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
    private lateinit var searchResponseGate: ElasticsearchSearchResponseGate

    @BeforeEach
    fun setupFixture() {
        searchResponseGate = ElasticsearchSearchResponseGate()
        client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch, searchResponseGate)
        fixture = ElasticsearchPortableQueryBackendFixture(
            client,
            QueryDocumentKind.SNAPSHOT,
            searchResponseGate,
        )
        StepVerifier.create(fixture.prepare(PortableQueryDataset)).verifyComplete()
    }

    override fun backendFactory(): ObservableQueryBackendFactory = fixture.backendFactory
    override fun prepare(dataset: PortableQueryDataset): Mono<Void> = fixture.prepare(dataset)
    override fun clear(): Mono<Void> = fixture.clear()
    override fun declaredCapabilities() = setOf(PortableQueryDataset.FULL_TEXT_CAPABILITY)

    @Test
    fun `constant keyword enum is projected through the real client`() {
        val target = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT)
        val indexName = target.namedAggregate.toSnapshotIndexName()
        val source = mapOf(
            "aggregateId" to "aggregate-1",
            "deleted" to false,
            "status" to "PROCESSING",
        )
        val factory = ElasticsearchObservableQueryBackendFactory(
            client,
            AtomicReference(),
            searchResponseGate,
        )
        val context = QueryBackendResolutionContext(
            target,
            PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
            MatchAll,
        )
        val allowedFields = setOf(
            LogicalField("aggregateId"),
            LogicalField("deleted"),
            PortableQueryDataset.STATUS,
        )
        val testKit = testKit(factory, QueryFieldAccess.Restricted(allowedFields))
        val request = ListQueryRequest(
            target = target,
            expression = MatchAll,
            resultShape = QueryResultShape.Dynamic,
            limit = 1,
        )
        val prepare = fixture.clear()
            .then(
                Mono.defer {
                    client.indices().create(CreateIndexRequest.of { it.index(indexName).mappings(enumProjectionMapping()) })
                },
            )
            .then(
                Mono.defer {
                    client.index(
                        IndexRequest.of<Map<String, Any>> {
                            it.index(indexName).id("constant-enum").document(source).refresh(Refresh.True)
                        },
                    )
                },
            )
            .then(factory.verifyRouteReadiness(context))

        StepVerifier.create(prepare.thenMany(testKit.gateway.list(request)))
            .assertNext { document ->
                document.keys.assert().isEqualTo(allowedFields.mapTo(LinkedHashSet(), LogicalField::value))
                document[PortableQueryDataset.STATUS.value].assert().isEqualTo("PROCESSING")
            }
            .verifyComplete()
    }

    @Test
    fun `normal completion closes the latest pit through the actual client publisher`() {
        val factory = fixture.backendFactory.apply { reset() }
        val request = ListQueryRequest(
            target = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
            expression = MatchAll,
            resultShape = QueryResultShape.Dynamic,
            limit = 0,
        )

        StepVerifier.create(withDataset(testKit(factory).gateway.list(request)))
            .expectNextCount(PortableQueryDataset.documents.count { !it.deleted }.toLong())
            .verifyComplete()

        factory.subscriptionCount(ElasticsearchQueryOperation.OPEN_PIT).assert().isOne()
        factory.subscriptionCount(ElasticsearchQueryOperation.SEARCH).assert().isOne()
        factory.subscriptionCount(ElasticsearchQueryOperation.CLOSE_PIT).assert().isOne()
        factory.cancellationCount(ElasticsearchQueryOperation.SEARCH).assert().isZero()
        factory.closedPitIds.assert().containsExactly(factory.latestPitId)
    }

    @Test
    fun `deadline cancels the actual search publisher and closes the latest pit`() {
        val factory = fixture.backendFactory.apply {
            reset()
            holdNextList(me.ahoo.wow.tck.query.backend.QueryBackendClientHold.BEFORE_FIRST_RESULT)
        }
        val vector = PortableQueryDataset.vectors.single {
            it.key == me.ahoo.wow.tck.query.backend.PortableContractKey.Lifecycle(
                me.ahoo.wow.tck.query.backend.PortableLifecycleCase.DEADLINE,
            )
        }
        val request = ListQueryRequest(
            target = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
            expression = vector.expression,
            resultShape = QueryResultShape.Dynamic,
            budget = QueryBudgetHint(timeout = Duration.ofSeconds(5)),
            limit = 0,
        )

        StepVerifier.create(withDataset(testKit(factory).gateway.list(request)))
            .then(factory::awaitHeldSearchRequest)
            .expectErrorSatisfies { error ->
                (error as QueryException).code.assert().isEqualTo(QueryErrorCode.DEADLINE_EXCEEDED)
            }
            .verify(Duration.ofSeconds(7))

        factory.subscriptionCount(ElasticsearchQueryOperation.SEARCH).assert().isOne()
        factory.cancellationCount(ElasticsearchQueryOperation.SEARCH).assert().isOne()
        factory.heldSearchRequestCount.assert().isOne()
        factory.heldSearchResponseCount.assert().isZero()
        factory.heldSearchTerminalAtCancellation.assert().isFalse()
        factory.heldSearchRequestPrecededCancellation.assert().isTrue()
        factory.heldSearchUpstreamCancelReturned.assert().isOne()
        factory.subscriptionCount(ElasticsearchQueryOperation.CLOSE_PIT).assert().isOne()
        factory.closedPitIds.assert().containsExactly(factory.latestPitId)
    }

    @Test
    fun `downstream cancellation cancels the actual search publisher and closes the latest pit`() {
        val factory = fixture.backendFactory.apply {
            reset()
            holdNextList(QueryBackendClientHold.AFTER_FIRST_RESULT)
        }
        val request = ListQueryRequest(
            target = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT),
            expression = MatchAll,
            resultShape = QueryResultShape.Dynamic,
            limit = 0,
        )

        StepVerifier.create(withDataset(testKit(factory).gateway.list(request)), 0)
            .thenRequest(1)
            .expectNextCount(1)
            .then(factory::awaitHeldSearchRequest)
            .thenCancel()
            .verify(Duration.ofSeconds(2))

        factory.subscriptionCount(ElasticsearchQueryOperation.SEARCH).assert().isEqualTo(2)
        factory.cancellationCount(ElasticsearchQueryOperation.SEARCH).assert().isOne()
        factory.heldSearchRequestCount.assert().isOne()
        factory.heldSearchResponseCount.assert().isZero()
        factory.heldSearchTerminalAtCancellation.assert().isFalse()
        factory.heldSearchRequestPrecededCancellation.assert().isTrue()
        factory.heldSearchUpstreamCancelReturned.assert().isOne()
        factory.subscriptionCount(ElasticsearchQueryOperation.CLOSE_PIT).assert().isOne()
        factory.closedPitIds.assert().containsExactly(factory.latestPitId)
    }

    @Test
    fun `legacy snapshot facade cancellation reaches each real search subscription`() {
        val testKit = testKit(fixture.backendFactory)
        val service = ElasticsearchSnapshotQueryService<Any>(
            testKit.target.namedAggregate,
            fixture.client,
            testKit.gateway,
        )

        fixture.verifyLegacyCancellation(service.dynamicList(ListQuery(Condition.ALL)))
    }

    private fun enumProjectionMapping(): TypeMapping = TypeMapping.of { mapping ->
        mapping.meta(
            ElasticsearchQueryReadiness.PRESENCE_VERSION_META,
            JsonData.of(ElasticsearchQueryPresenceEncoder.VERSION),
        ).properties("aggregateId", Property.of { it.keyword { value -> value } })
            .properties("deleted", Property.of { it.boolean_ { value -> value } })
            .properties(
                "status",
                Property.of { it.constantKeyword { value -> value.value(JsonData.of("PROCESSING")) } },
            )
    }
}
