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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryBudgetHint
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.ElasticsearchSearchResponseGate
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

class ElasticsearchSnapshotQueryBackendSpec : SnapshotQueryBackendSpec() {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture()
    private lateinit var fixture: ElasticsearchPortableQueryBackendFixture

    @BeforeEach
    fun setupFixture() {
        val searchResponseGate = ElasticsearchSearchResponseGate()
        fixture = ElasticsearchPortableQueryBackendFixture(
            ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch, searchResponseGate),
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
        factory.subscriptionCount(ElasticsearchQueryOperation.CLOSE_PIT).assert().isOne()
        factory.closedPitIds.assert().containsExactly(factory.latestPitId)
    }
}
