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

import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.CountRequest
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.SearchResponse
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.elasticsearch.indices.GetIndicesSettingsRequest
import co.elastic.clients.elasticsearch.indices.GetMappingRequest
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.QuerySortDirection
import me.ahoo.wow.elasticsearch.ElasticsearchSearchResponseGate
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.IndexTemplateInitializer
import me.ahoo.wow.elasticsearch.ReactiveElasticsearchClients
import me.ahoo.wow.elasticsearch.TemplateInitializer.createElasticsearchTemplate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.tck.container.ElasticsearchTestFixture
import me.ahoo.wow.tck.query.backend.ObservableQueryBackendFactory
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.QueryBackendClientHold
import me.ahoo.wow.tck.query.backend.QueryBackendTestKit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.reactivestreams.Subscription
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.CoreSubscriber
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoOperator
import reactor.test.StepVerifier
import reactor.util.context.Context
import java.time.Duration
import java.util.EnumMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ElasticsearchQueryResourceBoundTest {
    @JvmField
    @RegisterExtension
    val elasticsearch = ElasticsearchTestFixture("es_query_resource")

    private lateinit var client: ReactiveElasticsearchClient
    private lateinit var gate: ElasticsearchSearchResponseGate
    private lateinit var backendFactory: ElasticsearchResourceBackendFactory
    private lateinit var testKit: QueryBackendTestKit

    @BeforeEach
    fun prepareDocuments() {
        gate = ElasticsearchSearchResponseGate()
        client = ReactiveElasticsearchClients.createReactiveElasticsearchClient(elasticsearch, gate)
        StepVerifier.create(createResourceIndex()).expectNext(Unit).verifyComplete()
        assertResourceDocumentCount()
        backendFactory = ElasticsearchResourceBackendFactory(client, gate)
        testKit = QueryBackendTestKit(backendFactory, QueryDocumentKind.SNAPSHOT)
        verifyReadiness()
        backendFactory.reset()
    }

    @AfterEach
    fun clearIndex() {
        gate.reset()
        val index = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT).namedAggregate.toSnapshotIndexName()
        StepVerifier.create(client.indices().delete { request -> request.index(index) }.then()).verifyComplete()
    }

    @Test
    fun productionDefaultsStreamExactlyThreeBoundedPitPages() {
        StepVerifier.create(query(), 0)
            .expectSubscription()
            .then { assertEquals(0, backendFactory.operationRequests(ElasticsearchQueryOperation.OPEN_PIT)) }
            .thenRequest(3)
            .expectNextCount(3)
            .then { assertEquals(1, backendFactory.searches.size) }
            .thenRequest(253)
            .expectNextCount(253)
            .thenRequest(256)
            .expectNextCount(256)
            .thenRequest(1)
            .expectNextCount(1)
            .then {
                assertEquals(3, backendFactory.searches.size)
                assertEquals(1, backendFactory.searches[2].hitCount)
            }
            .expectComplete()
            .verify(Duration.ofSeconds(15))

        assertEquals(3, backendFactory.searches.size)
        backendFactory.searches.forEach { search -> assertTrue(search.size <= DEFAULT_PAGE_SIZE) }
        assertEquals(RESOURCE_DOCUMENTS, backendFactory.searches.sumOf(ResourceSearchObservation::hitCount))
        assertEquals(
            backendFactory.searches[0].lastSort,
            backendFactory.searches[1].searchAfter,
        )
        assertEquals(
            backendFactory.searches[1].lastSort,
            backendFactory.searches[2].searchAfter,
        )
        assertTrue(backendFactory.maxPendingSearches.get() <= 1)
        assertTrue(backendFactory.maxApplicationBuffered.get() <= DEFAULT_PAGE_SIZE)
        assertEquals(0, backendFactory.applicationBuffered.get())
        assertEquals(1, backendFactory.operationRequests(ElasticsearchQueryOperation.OPEN_PIT))
        assertEquals(1, backendFactory.operationRequests(ElasticsearchQueryOperation.CLOSE_PIT))
        assertEquals(listOf(backendFactory.latestPitId.get()), backendFactory.closedPitIds)
        assertEquals(0, backendFactory.cancellationCount)
    }

    @Test
    fun earlyCancellationClosesPitWithoutRequestingAnotherPage() {
        StepVerifier.create(query(), 0)
            .expectSubscription()
            .thenRequest(3)
            .expectNextCount(3)
            .thenCancel()
            .verify()

        assertTrue(backendFactory.awaitClose())
        assertEquals(1, backendFactory.searches.size)
        assertEquals(1, backendFactory.operationRequests(ElasticsearchQueryOperation.OPEN_PIT))
        assertEquals(1, backendFactory.operationRequests(ElasticsearchQueryOperation.CLOSE_PIT))
        assertEquals(listOf(backendFactory.latestPitId.get()), backendFactory.closedPitIds)
        assertEquals(0, backendFactory.postCancelOpenOrSearch.get())
    }

    @Test
    fun lateCancellationAbortsTheRealSecondHttpSearchAndClosesLatestPit() {
        backendFactory.armSecondSearchGate()
        StepVerifier.create(query(), 0)
            .expectSubscription()
            .thenRequest(257)
            .expectNextCount(256)
            .then { backendFactory.awaitHeldSecondSearch() }
            .thenCancel()
            .verify()

        assertTrue(backendFactory.awaitUpstreamCancelReturned())
        assertTrue(backendFactory.awaitClose())
        assertEquals(2, backendFactory.searches.size)
        val held = backendFactory.searches[1]
        assertEquals(0, held.responseCount.get())
        assertEquals(0, held.terminalCount.get())
        assertEquals(1, backendFactory.cancellationCount)
        assertEquals(0, backendFactory.postCancelOpenOrSearch.get())
        assertEquals(1, backendFactory.operationRequests(ElasticsearchQueryOperation.OPEN_PIT))
        assertEquals(1, backendFactory.operationRequests(ElasticsearchQueryOperation.CLOSE_PIT))
        assertEquals(listOf(backendFactory.latestPitId.get()), backendFactory.closedPitIds)
    }

    private fun query() = testKit.gateway.list(
        ListQueryRequest(
            target = testKit.target,
            expression = MatchAll,
            resultShape = QueryResultShape.Typed(
                ResourceElasticsearchResult::class.java,
                QueryProjection.Include(setOf(AGGREGATE_ID)),
            ),
            sort = listOf(QuerySort(AGGREGATE_ID, QuerySortDirection.ASC)),
            limit = 0,
        ),
    ).doOnNext { backendFactory.recordApplicationEmission() }
        .map(ResourceElasticsearchResult::aggregateId)

    private fun createResourceIndex(): Mono<Unit> {
        val index = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT).namedAggregate.toSnapshotIndexName()
        val request = BulkRequest.of { bulk ->
            for (ordinal in 1..RESOURCE_DOCUMENTS) {
                val id = "resource-${ordinal.toString().padStart(4, '0')}"
                val source = ElasticsearchQueryPresenceEncoder.encode(
                    mapOf("aggregateId" to id, "deleted" to false),
                )
                bulk.operations { operation ->
                    operation.index { indexOperation -> indexOperation.index(index).id(id).document(source) }
                }
            }
            bulk.refresh(Refresh.True)
        }
        return IndexTemplateInitializer(client.createElasticsearchTemplate()).ensureAllTemplates()
            .then(Mono.defer { client.indices().create(CreateIndexRequest.of { create -> create.index(index) }) })
            .then(Mono.defer { client.bulk(request) }).flatMap { response ->
                if (response.errors()) {
                    Mono.error(AssertionError("Resource fixture bulk request failed."))
                } else {
                    Mono.just(Unit)
                }
            }
    }

    private fun assertResourceDocumentCount() {
        val index = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT).namedAggregate.toSnapshotIndexName()
        StepVerifier.create(
            client.count(CountRequest.of { request -> request.index(index).query { query -> query.matchAll { it } } }),
        ).assertNext { response -> assertEquals(RESOURCE_DOCUMENTS.toLong(), response.count()) }
            .verifyComplete()
    }

    private fun verifyReadiness() {
        assertProductionTemplateMapping()
        val context = QueryBackendResolutionContext(
            testKit.target,
            PortableQueryDataset.schema(QueryDocumentKind.SNAPSHOT),
            MatchAll,
        )
        StepVerifier.create(backendFactory.bind(context).readiness())
            .expectNext(QueryBackendReadiness.Ready)
            .verifyComplete()
    }

    private fun assertProductionTemplateMapping() {
        val index = PortableQueryDataset.target(QueryDocumentKind.SNAPSHOT).namedAggregate.toSnapshotIndexName()
        val mapping = client.indices().getMapping(GetMappingRequest.of { request -> request.index(index) })
        val settings = client.indices().getSettings(
            GetIndicesSettingsRequest.of { request -> request.index(index).includeDefaults(true) },
        )
        StepVerifier.create(mapping.zipWith(settings))
            .assertNext { responses ->
                val mappingResponse = responses.t1
                val settingsResponse = responses.t2
                assertEquals(mappingResponse.mappings().keys, settingsResponse.settings().keys)
                val actual = mappingResponse.mappings().getValue(index).mappings().meta()
                    .getValue(ElasticsearchQueryReadiness.PRESENCE_VERSION_META)
                    .to(Int::class.javaObjectType)
                assertEquals(ElasticsearchQueryPresenceEncoder.VERSION, actual)
            }
            .verifyComplete()
    }

    private companion object {
        const val RESOURCE_DOCUMENTS: Int = 513
        const val DEFAULT_PAGE_SIZE: Int = 256
        val AGGREGATE_ID = LogicalField("aggregateId")
    }
}

private data class ResourceElasticsearchResult(val aggregateId: String)

private data class ResourceSearchObservation(
    val size: Int,
    val pitId: String?,
    val searchAfter: List<FieldValue>,
    val responseCount: AtomicLong = AtomicLong(),
    val terminalCount: AtomicLong = AtomicLong(),
    @Volatile var hitCount: Int = 0,
    @Volatile var lastSort: List<FieldValue> = emptyList(),
)

private class ElasticsearchResourceBackendFactory(
    private val client: ReactiveElasticsearchClient,
    private val gate: ElasticsearchSearchResponseGate,
) : ObservableQueryBackendFactory, ElasticsearchQueryPublisherObserver {
    private val delegate = ElasticsearchQueryBackendFactory(client)
    private val operationProbes = EnumMap<ElasticsearchQueryOperation, ResourceOperationProbe>(
        ElasticsearchQueryOperation::class.java,
    ).apply { ElasticsearchQueryOperation.entries.forEach { put(it, ResourceOperationProbe()) } }
    private val preparedSearches = ConcurrentLinkedQueue<ResourceSearchObservation>()
    private val decoratedSearches = AtomicInteger()
    private val heldSearchToken = AtomicReference<String?>()
    private val heldSecondSearch = AtomicReference<ResourceSearchObservation?>()
    private var closeLatch = CountDownLatch(1)
    private var upstreamCancelReturned = CountDownLatch(1)
    val searches = CopyOnWriteArrayList<ResourceSearchObservation>()
    val closedPitIds = CopyOnWriteArrayList<String>()
    val latestPitId = AtomicReference<String?>()
    val maxPendingSearches = AtomicLong()
    val applicationBuffered = AtomicLong()
    val maxApplicationBuffered = AtomicLong()
    val postCancelOpenOrSearch = AtomicLong()
    private val cancelPhase = AtomicBoolean()

    init {
        ElasticsearchQueryPublisherObservers.install(client, this)
    }

    override val subscriptionCount: Long
        get() = probe(ElasticsearchQueryOperation.SEARCH).subscriptions.get()
    override val cancellationCount: Long
        get() = probe(ElasticsearchQueryOperation.SEARCH).cancellations.get()

    override fun bind(context: QueryBackendResolutionContext): QueryBackend = delegate.bind(context)

    override fun reset() {
        gate.reset()
        operationProbes.values.forEach(ResourceOperationProbe::reset)
        preparedSearches.clear()
        decoratedSearches.set(0)
        heldSearchToken.set(null)
        heldSecondSearch.set(null)
        closeLatch = CountDownLatch(1)
        upstreamCancelReturned = CountDownLatch(1)
        searches.clear()
        closedPitIds.clear()
        latestPitId.set(null)
        maxPendingSearches.set(0)
        applicationBuffered.set(0)
        maxApplicationBuffered.set(0)
        postCancelOpenOrSearch.set(0)
        cancelPhase.set(false)
    }

    override fun holdNextList(hold: QueryBackendClientHold) {
        error("Resource-bound fixture does not emulate client holds: $hold")
    }

    override fun awaitHeldClientPublisher() = awaitHeldSecondSearch()

    override fun decorateSearch(request: SearchRequest): SearchRequest {
        val ordinal = decoratedSearches.incrementAndGet()
        val decorated = if (ordinal == 2 && heldSearchToken.get() != null) {
            request.rebuild().preference(heldSearchToken.get()).build()
        } else {
            request
        }
        preparedSearches += ResourceSearchObservation(
            decorated.size() ?: 0,
            decorated.pit()?.id(),
            decorated.searchAfter().toList(),
        )
        return decorated
    }

    override fun updatePitId(pitId: String) {
        latestPitId.set(pitId)
    }

    override fun <T : Any> observe(
        context: ElasticsearchQueryOperationContext,
        publisher: Mono<T>,
    ): Mono<T> {
        val search = if (context.operation == ElasticsearchQueryOperation.SEARCH) {
            checkNotNull(preparedSearches.poll()) { "Observed SEARCH has no decorated request." }
        } else {
            null
        }
        return ResourceObservedMono(publisher, context, search, this)
    }

    fun armSecondSearchGate() {
        heldSearchToken.set(gate.arm())
    }

    fun awaitHeldSecondSearch() {
        check(gate.awaitIntercepted()) { "Second SEARCH response did not reach the real HTTP gate." }
        val held = checkNotNull(heldSecondSearch.get()) { "Second SEARCH was not requested." }
        check(held.responseCount.get() == 0L) { "Held SEARCH produced a raw response." }
        check(held.terminalCount.get() == 0L) { "Held SEARCH terminated before cancellation." }
    }

    fun awaitUpstreamCancelReturned(): Boolean = upstreamCancelReturned.await(5, TimeUnit.SECONDS)

    fun awaitClose(): Boolean = closeLatch.await(5, TimeUnit.SECONDS)

    fun operationRequests(operation: ElasticsearchQueryOperation): Long = probe(operation).requests.get()

    fun recordApplicationEmission() {
        applicationBuffered.decrementAndGet()
    }

    private fun probe(operation: ElasticsearchQueryOperation): ResourceOperationProbe = operationProbes.getValue(
        operation,
    )

    private fun recordRequest(
        context: ElasticsearchQueryOperationContext,
        search: ResourceSearchObservation?,
    ) {
        val probe = probe(context.operation)
        probe.requests.incrementAndGet()
        if (context.operation in setOf(ElasticsearchQueryOperation.OPEN_PIT, ElasticsearchQueryOperation.SEARCH) &&
            cancelPhase.get()
        ) {
            postCancelOpenOrSearch.incrementAndGet()
        }
        if (search != null) {
            searches += search
            val pending = probe.pendingResponses.incrementAndGet()
            maxPendingSearches.accumulateAndGet(pending, ::maxOf)
            if (context.queryToken == heldSearchToken.get()) {
                heldSecondSearch.set(search)
            }
        }
        if (context.operation == ElasticsearchQueryOperation.CLOSE_PIT) {
            context.pitId?.let(closedPitIds::add)
        }
    }

    private fun recordResponse(
        context: ElasticsearchQueryOperationContext,
        search: ResourceSearchObservation?,
        value: Any,
    ) {
        probe(context.operation).responses.incrementAndGet()
        if (context.operation == ElasticsearchQueryOperation.CLOSE_PIT) {
            closeLatch.countDown()
        }
        if (search != null) {
            search.responseCount.incrementAndGet()
            probe(context.operation).pendingResponses.decrementAndGet()
            val response = value as SearchResponse<*>
            search.hitCount = response.hits().hits().size
            search.lastSort = response.hits().hits().lastOrNull()?.sort() ?: emptyList()
            val buffered = applicationBuffered.addAndGet(search.hitCount.toLong())
            maxApplicationBuffered.accumulateAndGet(buffered, ::maxOf)
        }
    }

    private fun recordTerminal(search: ResourceSearchObservation?) {
        search?.terminalCount?.incrementAndGet()
    }

    private fun recordCancellation(
        context: ElasticsearchQueryOperationContext,
        search: ResourceSearchObservation?,
    ) {
        val probe = probe(context.operation)
        probe.cancellations.incrementAndGet()
        if (search != null && search.responseCount.get() == 0L) {
            probe.pendingResponses.decrementAndGet()
        }
        cancelPhase.set(true)
    }

    private class ResourceObservedMono<T : Any>(
        source: Mono<out T>,
        private val context: ElasticsearchQueryOperationContext,
        private val search: ResourceSearchObservation?,
        private val owner: ElasticsearchResourceBackendFactory,
    ) : MonoOperator<T, T>(source) {
        override fun subscribe(actual: CoreSubscriber<in T>) {
            source.subscribe(ResourceObservedSubscriber(actual, context, search, owner))
        }
    }

    private class ResourceObservedSubscriber<T : Any>(
        private val downstream: CoreSubscriber<in T>,
        private val context: ElasticsearchQueryOperationContext,
        private val search: ResourceSearchObservation?,
        private val owner: ElasticsearchResourceBackendFactory,
    ) : CoreSubscriber<T>, Subscription {
        private lateinit var upstream: Subscription
        private val requested = AtomicBoolean()
        private val responseSeen = AtomicBoolean()
        private val terminated = AtomicBoolean()

        override fun currentContext(): Context = downstream.currentContext()

        override fun onSubscribe(subscription: Subscription) {
            upstream = subscription
            owner.probe(context.operation).subscriptions.incrementAndGet()
            downstream.onSubscribe(this)
        }

        override fun request(n: Long) {
            if (n > 0 && requested.compareAndSet(false, true)) {
                owner.recordRequest(context, search)
            }
            upstream.request(n)
        }

        override fun cancel() {
            val inFlight = !responseSeen.get() && terminated.compareAndSet(false, true)
            if (inFlight) {
                owner.recordCancellation(context, search)
            }
            try {
                upstream.cancel()
                if (inFlight && search === owner.heldSecondSearch.get()) {
                    owner.upstreamCancelReturned.countDown()
                }
            } finally {
                if (inFlight && search === owner.heldSecondSearch.get()) {
                    owner.gate.release()
                }
            }
        }

        override fun onNext(value: T) {
            if (terminated.get()) {
                return
            }
            responseSeen.set(true)
            owner.recordResponse(context, search, value)
            downstream.onNext(value)
        }

        override fun onError(error: Throwable) {
            if (terminated.compareAndSet(false, true)) {
                owner.recordTerminal(search)
                downstream.onError(error)
            }
        }

        override fun onComplete() {
            if (terminated.compareAndSet(false, true)) {
                owner.recordTerminal(search)
                downstream.onComplete()
            }
        }
    }
}

private class ResourceOperationProbe {
    val subscriptions = AtomicLong()
    val requests = AtomicLong()
    val responses = AtomicLong()
    val cancellations = AtomicLong()
    val pendingResponses = AtomicLong()

    fun reset() {
        subscriptions.set(0)
        requests.set(0)
        responses.set(0)
        cancellations.set(0)
        pendingResponses.set(0)
    }
}
