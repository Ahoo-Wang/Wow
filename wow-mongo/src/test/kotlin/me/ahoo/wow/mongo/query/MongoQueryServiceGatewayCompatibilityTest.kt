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

import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.Called
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
import me.ahoo.wow.mongo.query.event.EventStreamConditionConverter
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryService
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryServiceFactory
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryService
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryServiceFactory
import me.ahoo.wow.mongo.query.snapshot.SnapshotConditionConverter
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.bson.Document
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MongoQueryServiceGatewayCompatibilityTest {
    private val collection = mockk<MongoCollection<Document>>(relaxed = true)

    @Test
    fun `storage-only services fail closed without converter or collection access`() {
        val snapshot = MongoSnapshotQueryService<Any>(
            MOCK_AGGREGATE_METADATA,
            collection,
            SnapshotConditionConverter
        )
        val event = MongoEventStreamQueryService(
            MOCK_AGGREGATE_METADATA,
            collection,
            EventStreamConditionConverter
        )
        clearMocks(collection)

        assertUnavailable(snapshot)
        assertUnavailable(event)

        verify { collection wasNot Called }
    }

    @Test
    fun `storage-only factories fail closed without collection execution`() {
        val database = mockk<MongoDatabase>()
        val snapshotCollection = mockk<MongoCollection<Document>>(relaxed = true)
        val eventCollection = mockk<MongoCollection<Document>>(relaxed = true)
        io.mockk.every { database.getCollection(any()) } returnsMany listOf(snapshotCollection, eventCollection)
        val snapshot = MongoSnapshotQueryServiceFactory(database).create<Any>(MOCK_AGGREGATE_METADATA)
        val event = MongoEventStreamQueryServiceFactory(database).create(MOCK_AGGREGATE_METADATA)
        clearMocks(snapshotCollection, eventCollection)

        assertUnavailable(snapshot)
        assertUnavailable(event)

        verify { snapshotCollection wasNot Called }
        verify { eventCollection wasNot Called }
    }

    @Test
    fun `explicit gateway services delegate all seven methods once per subscription`() {
        mockkObject(SnapshotConditionConverter, EventStreamConditionConverter)
        try {
            every { SnapshotConditionConverter.convert(any()) } throws AssertionError("legacy snapshot converter invoked")
            every { EventStreamConditionConverter.convert(any()) } throws AssertionError("legacy event converter invoked")

            val snapshotGateway = RecordingGateway()
            val snapshot = MongoSnapshotQueryService<Any>(MOCK_AGGREGATE_METADATA, collection, snapshotGateway)
            clearMocks(collection)
            assertGatewayDelegation(snapshot, snapshotGateway, QueryDocumentKind.SNAPSHOT)
            verify { collection wasNot Called }
            verify(exactly = 0) { SnapshotConditionConverter.convert(any()) }

            val eventGateway = RecordingGateway()
            val event = MongoEventStreamQueryService(MOCK_AGGREGATE_METADATA, collection, eventGateway)
            clearMocks(collection)
            assertGatewayDelegation(event, eventGateway, QueryDocumentKind.EVENT_STREAM)
            verify { collection wasNot Called }
            verify(exactly = 0) { EventStreamConditionConverter.convert(any()) }
        } finally {
            unmockkObject(SnapshotConditionConverter, EventStreamConditionConverter)
        }
    }

    @Test
    fun `explicit gateway factories preserve caching and delegate all seven methods`() {
        val database = mockk<MongoDatabase>()
        val snapshotCollection = mockk<MongoCollection<Document>>(relaxed = true)
        val eventCollection = mockk<MongoCollection<Document>>(relaxed = true)
        io.mockk.every { database.getCollection(any()) } returnsMany listOf(snapshotCollection, eventCollection)
        val snapshotGateway = RecordingGateway()
        val snapshotFactory = MongoSnapshotQueryServiceFactory(database, snapshotGateway)
        val snapshot = snapshotFactory.create<Any>(MOCK_AGGREGATE_METADATA)
        snapshotFactory.create<Any>(MOCK_AGGREGATE_METADATA).assert().isSameAs(snapshot)
        clearMocks(snapshotCollection)
        assertGatewayDelegation(snapshot, snapshotGateway, QueryDocumentKind.SNAPSHOT)
        verify { snapshotCollection wasNot Called }

        val eventGateway = RecordingGateway()
        val eventFactory = MongoEventStreamQueryServiceFactory(database, eventGateway)
        val event = eventFactory.create(MOCK_AGGREGATE_METADATA)
        eventFactory.create(MOCK_AGGREGATE_METADATA).assert().isSameAs(event)
        clearMocks(eventCollection)
        assertGatewayDelegation(event, eventGateway, QueryDocumentKind.EVENT_STREAM)
        verify { eventCollection wasNot Called }
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
