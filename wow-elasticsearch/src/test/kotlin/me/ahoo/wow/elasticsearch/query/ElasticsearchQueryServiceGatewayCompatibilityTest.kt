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

import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryConsistency
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.api.query.gateway.QueryRequest
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryService
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryServiceFactory
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryService
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceFactory
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ElasticsearchQueryServiceGatewayCompatibilityTest {
    private val client = mockk<ReactiveElasticsearchClient>(relaxed = true)

    @Test
    fun `storage-only services fail closed without client access`() {
        val snapshot = ElasticsearchSnapshotQueryService<Any>(MOCK_AGGREGATE_METADATA, client)
        val event = ElasticsearchEventStreamQueryService(MOCK_AGGREGATE_METADATA, client)
        clearMocks(client)

        assertUnavailable(snapshot)
        assertUnavailable(event)

        verify { client wasNot Called }
    }

    @Test
    fun `storage-only factories fail closed without client access`() {
        val snapshot = ElasticsearchSnapshotQueryServiceFactory(client).create<Any>(MOCK_AGGREGATE_METADATA)
        val event = ElasticsearchEventStreamQueryServiceFactory(client).create(MOCK_AGGREGATE_METADATA)
        clearMocks(client)

        assertUnavailable(snapshot)
        assertUnavailable(event)

        verify { client wasNot Called }
    }

    @Test
    fun `explicit gateway services delegate all seven methods once per subscription`() {
        val snapshotGateway = RecordingGateway()
        val snapshot = ElasticsearchSnapshotQueryService<Any>(MOCK_AGGREGATE_METADATA, client, snapshotGateway)
        clearMocks(client)
        assertGatewayDelegation(snapshot, snapshotGateway, QueryDocumentKind.SNAPSHOT)
        verify { client wasNot Called }

        val eventGateway = RecordingGateway()
        val event = ElasticsearchEventStreamQueryService(MOCK_AGGREGATE_METADATA, client, eventGateway)
        clearMocks(client)
        assertGatewayDelegation(event, eventGateway, QueryDocumentKind.EVENT_STREAM)
        verify { client wasNot Called }
    }

    @Test
    fun `explicit gateway factories preserve caching and delegate all seven methods`() {
        val snapshotGateway = RecordingGateway()
        val snapshotFactory = ElasticsearchSnapshotQueryServiceFactory(client, snapshotGateway)
        val snapshot = snapshotFactory.create<Any>(MOCK_AGGREGATE_METADATA)
        snapshotFactory.create<Any>(MOCK_AGGREGATE_METADATA).assert().isSameAs(snapshot)
        clearMocks(client)
        assertGatewayDelegation(snapshot, snapshotGateway, QueryDocumentKind.SNAPSHOT)
        verify { client wasNot Called }

        val eventGateway = RecordingGateway()
        val eventFactory = ElasticsearchEventStreamQueryServiceFactory(client, eventGateway)
        val event = eventFactory.create(MOCK_AGGREGATE_METADATA)
        eventFactory.create(MOCK_AGGREGATE_METADATA).assert().isSameAs(event)
        clearMocks(client)
        assertGatewayDelegation(event, eventGateway, QueryDocumentKind.EVENT_STREAM)
        verify { client wasNot Called }
    }

    private fun assertUnavailable(service: QueryService<*>) {
        publishers(service).forEach { (publisher) ->
            repeat(SUBSCRIPTIONS_PER_PUBLISHER) {
                StepVerifier.create(publisher).expectErrorSatisfies(::assertBackendUnavailable).verify()
            }
        }
    }

    private fun assertGatewayDelegation(
        service: QueryService<*>,
        gateway: RecordingGateway,
        kind: QueryDocumentKind
    ) {
        val publishers = publishers(service)
        gateway.calls.get().assert().isZero()
        publishers.forEach { (publisher, nextCount) ->
            repeat(SUBSCRIPTIONS_PER_PUBLISHER) {
                val before = gateway.calls.get()
                StepVerifier.create(publisher).expectNextCount(nextCount).verifyComplete()
                gateway.calls.get().assert().isEqualTo(before + 1)
            }
        }
        gateway.calls.get().assert().isEqualTo(7 * SUBSCRIPTIONS_PER_PUBLISHER)
        gateway.calls.get().assert().isEqualTo(gateway.requests.size)
        gateway.requests.map { it.target.documentKind }.assert().containsOnly(kind)
    }

    private fun publishers(service: QueryService<*>): List<Pair<Publisher<*>, Long>> = listOf(
        service.single(SingleQuery(Condition.ALL)) to 0L,
        service.dynamicSingle(SingleQuery(Condition.ALL)) to 0L,
        service.list(ListQuery(Condition.ALL)) to 0L,
        service.dynamicList(ListQuery(Condition.ALL)) to 0L,
        service.paged(PagedQuery(Condition.ALL)) to 1L,
        service.dynamicPaged(PagedQuery(Condition.ALL)) to 1L,
        service.count(Condition.ALL) to 1L
    )

    private fun assertBackendUnavailable(error: Throwable) {
        (error as QueryException).apply {
            code.assert().isEqualTo(QueryErrorCode.BACKEND_NOT_READY)
            stage.assert().isEqualTo(QueryStage.BACKEND_RESOLUTION)
            reason.assert().isEqualTo(QueryErrorReason.BACKEND_UNAVAILABLE)
            causeCode.assert().isNull()
        }
    }

    companion object {
        private const val SUBSCRIPTIONS_PER_PUBLISHER = 2
    }
}

private class RecordingGateway : QueryGateway {
    val calls = AtomicInteger()
    val requests = CopyOnWriteArrayList<QueryRequest>()

    override fun <R : Any> single(request: SingleQueryRequest<R>): Mono<R> = Mono.defer {
        record(request)
        Mono.empty()
    }

    override fun <R : Any> list(request: ListQueryRequest<R>): Flux<R> = Flux.defer {
        record(request)
        Flux.empty()
    }

    override fun <R : Any> page(request: PageQueryRequest<R>): Mono<QueryPage<R>> = Mono.fromSupplier {
        record(request)
        QueryPage(emptyList(), 0, QueryConsistency.EXACT)
    }

    override fun count(request: CountQueryRequest): Mono<Long> = Mono.fromSupplier {
        record(request)
        0
    }

    private fun record(request: QueryRequest) {
        calls.incrementAndGet()
        requests += request
    }
}
