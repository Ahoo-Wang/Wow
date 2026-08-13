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
import co.elastic.clients.elasticsearch.core.OpenPointInTimeRequest
import co.elastic.clients.elasticsearch.core.ClosePointInTimeRequest
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import co.elastic.clients.json.JsonData
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.elasticsearch.IndexNameConverter.toEventStreamIndexName
import me.ahoo.wow.elasticsearch.IndexNameConverter.toSnapshotIndexName
import me.ahoo.wow.elasticsearch.ElasticsearchSearchResponseGate
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.tck.query.backend.ObservableQueryBackendFactory
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.QueryBackendClientHold
import org.reactivestreams.Subscription
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.CoreSubscriber
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoOperator
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.util.Base64
import java.util.EnumMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class ElasticsearchPortableQueryBackendFixture(
    private val client: ReactiveElasticsearchClient,
    private val documentKind: QueryDocumentKind,
    searchResponseGate: ElasticsearchSearchResponseGate,
) {
    private val indexName = when (documentKind) {
        QueryDocumentKind.SNAPSHOT -> PortableQueryDataset.target(documentKind).namedAggregate.toSnapshotIndexName()
        QueryDocumentKind.EVENT_STREAM -> PortableQueryDataset.target(documentKind).namedAggregate.toEventStreamIndexName()
    }
    private val prepared = AtomicBoolean()
    private val preparedPitId = AtomicReference<String>()
    val backendFactory = ElasticsearchObservableQueryBackendFactory(client, preparedPitId, searchResponseGate)

    fun prepare(dataset: PortableQueryDataset): Mono<Void> {
        if (prepared.get()) return Mono.empty()
        val schema = dataset.schema(documentKind)
        val create = client.indices().create(
            CreateIndexRequest.of { request ->
                request.index(indexName).mappings(mapping(schema.fields.values.toList()))
            },
        )
        val writes = Flux.fromIterable(dataset.storedDocuments(documentKind)).concatMap { stored ->
            val source = LinkedHashMap<String, Any?>()
            stored.fields.forEach { (field, value) ->
                if ('.' !in field.value) {
                    source[field.value] = value.toElasticsearchValue(schema.fields.getValue(field).system)
                }
            }
            if (documentKind == QueryDocumentKind.EVENT_STREAM) {
                val body = LinkedHashMap<String, Any?>()
                stored.fields.forEach { (field, value) ->
                    if (field.value.startsWith("body.")) {
                        body[field.value.removePrefix("body.")] =
                            value.toElasticsearchValue(schema.fields.getValue(field).system)
                    }
                }
                source["body"] = listOf(body)
            }
            val encoded = ElasticsearchQueryPresenceEncoder.encode(source)
            client.index(
                IndexRequest.of<Map<String, Any?>> { request ->
                    request.index(indexName).id(stored.logicalId).document(encoded).refresh(Refresh.True)
                },
            )
        }
        return create.thenMany(writes).then()
            .doOnSuccess { prepared.set(true) }
            .then(verifyReadiness(dataset))
            .then(preparePit())
    }

    fun clear(): Mono<Void> = Mono.defer {
        val preparedPit = preparedPitId.getAndSet(null)?.let { pitId ->
            client.closePointInTime(ClosePointInTimeRequest.of { request -> request.id(pitId) })
                .onErrorResume { Mono.empty() }
                .then()
        } ?: Mono.empty()
        preparedPit.then(client.indices().delete { request -> request.index(indexName) })
    }.onErrorResume { Mono.empty() }
        .doOnSuccess { prepared.set(false) }
        .then()

    private fun verifyReadiness(dataset: PortableQueryDataset): Mono<Void> = Mono.defer {
        val context = QueryBackendResolutionContext(
            dataset.target(documentKind),
            dataset.schema(documentKind),
            dataset.vectors.first().expression,
        )
        backendFactory.verifyRouteReadiness(context)
    }

    private fun preparePit(): Mono<Void> = Mono.defer {
        client.openPointInTime(
            OpenPointInTimeRequest.of { request ->
                request.index(indexName).keepAlive { keepAlive -> keepAlive.time("1m") }
            },
        ).doOnNext { opened -> preparedPitId.set(opened.id()) }.then()
    }

    private fun mapping(fields: List<QueryFieldSchema>): TypeMapping {
        val root = MappingNode()
        fields.forEach { field ->
            var current = root
            field.path.value.split('.').forEach { segment -> current = current.children.getOrPut(segment, ::MappingNode) }
            current.schema = field
        }
        return TypeMapping.of { mapping ->
            mapping.meta(ElasticsearchQueryReadiness.PRESENCE_VERSION_META, JsonData.of(ElasticsearchQueryPresenceEncoder.VERSION))
            root.children.forEach { (name, node) -> mapping.properties(name, node.toProperty()) }
            mapping.properties("__wow_query", metadataProperty())
        }
    }

    private fun MappingNode.toProperty(): Property {
        val field = schema
        if (children.isNotEmpty() || field?.valueKind in setOf(QueryFieldValueKind.OBJECT, QueryFieldValueKind.MAP)) {
            val nested = field?.collectionKind == QueryCollectionKind.OBJECT || field?.elementMatchEnabled == true
            return Property.of { property ->
                if (nested) {
                    property.nested { builder ->
                        children.forEach { (name, node) -> builder.properties(name, node.toProperty()) }
                        builder.properties("__wow_query", metadataProperty())
                    }
                } else {
                    property.`object` { builder ->
                        children.forEach { (name, node) -> builder.properties(name, node.toProperty()) }
                        builder.properties("__wow_query", metadataProperty())
                    }
                }
            }
        }
        return when (field?.valueKind) {
            QueryFieldValueKind.BOOLEAN -> Property.of { it.boolean_ { value -> value } }
            QueryFieldValueKind.INTEGER -> Property.of { it.long_ { value -> value } }
            QueryFieldValueKind.DECIMAL -> Property.of { it.double_ { value -> value } }
            QueryFieldValueKind.TIME -> Property.of { if (field.system) it.long_ { value -> value } else it.date { value -> value } }
            QueryFieldValueKind.BINARY -> Property.of { it.binary { value -> value } }
            QueryFieldValueKind.STRING -> if (me.ahoo.wow.api.query.expression.QueryCapabilityId("full-text") in field.capabilities) {
                Property.of { it.text { text -> text.fields("exact") { exact -> exact.keyword { keyword -> keyword } } } }
            } else Property.of { it.keyword { value -> value } }
            QueryFieldValueKind.ENUM -> Property.of { it.keyword { value -> value } }
            else -> Property.of { it.keyword { value -> value } }
        }
    }

    private fun metadataProperty(): Property = Property.of { property ->
        property.`object` { obj ->
            obj.properties("present") { it.keyword { value -> value } }
                .properties("null") { it.keyword { value -> value } }
        }
    }

    private class MappingNode(
        var schema: QueryFieldSchema? = null,
        val children: LinkedHashMap<String, MappingNode> = LinkedHashMap(),
    )
}

internal class ElasticsearchObservableQueryBackendFactory(
    client: ReactiveElasticsearchClient,
    preparedPitId: AtomicReference<String>,
    private val searchResponseGate: ElasticsearchSearchResponseGate,
) : ObservableQueryBackendFactory, ElasticsearchQueryPublisherObserver {
    private val latestObservedPitId = AtomicReference<String>()
    private val delegate = ElasticsearchQueryBackendFactory(client)
    private val preparedDelegate = ElasticsearchQueryBackendBinder(
        client,
        ElasticsearchNativeQueryTemplateRegistry(),
        me.ahoo.wow.query.validation.QueryBudgetLimit.UNBOUNDED,
    )
    private val lifecycleDelegate = ElasticsearchQueryBackendBinder(
        client,
        ElasticsearchNativeQueryTemplateRegistry(),
        me.ahoo.wow.query.validation.QueryBudgetLimit.UNBOUNDED,
        pitPageSize = 1,
        prefetchFirstPitPage = true,
        prefetchBarrier = searchResponseGate::intercepted,
        transportFactory = { reactiveClient ->
            PreparedPitElasticsearchQueryTransport(
                ReactiveClientElasticsearchQueryTransport(reactiveClient),
                preparedPitId,
                latestObservedPitId,
            )
        },
    )
    private val nextHold = AtomicReference<ArmedSearchHold?>()
    private val operationProbes = EnumMap<ElasticsearchQueryOperation, ElasticsearchClientOperationProbe>(
        ElasticsearchQueryOperation::class.java,
    ).apply {
        ElasticsearchQueryOperation.entries.forEach { operation -> put(operation, ElasticsearchClientOperationProbe()) }
    }
    private val observedClosedPitIds = ConcurrentLinkedQueue<String>()
    private val routeReadinessVerified = AtomicBoolean()
    private val preparedMappingSnapshot = AtomicReference<ElasticsearchQueryMappingSnapshot>()

    init {
        ElasticsearchQueryPublisherObservers.install(client, this)
    }

    override val subscriptionCount: Long get() = probe(ElasticsearchQueryOperation.SEARCH).heldSubscriptions.get()
    override val cancellationCount: Long
        get() {
            val search = probe(ElasticsearchQueryOperation.SEARCH)
            check(search.heldRequests.get() == 1L) { "Held Elasticsearch SEARCH was not requested." }
            check(search.heldResponses.get() == 0L) { "Held Elasticsearch SEARCH produced a raw response." }
            check(search.awaitHeldCancellation()) { "Held Elasticsearch SEARCH was not cancelled." }
            check(search.awaitUpstreamCancelReturned()) {
                "Held Elasticsearch SEARCH upstream cancellation did not return."
            }
            check(search.terminalAtCancellation.get() == false) {
                "Held Elasticsearch SEARCH terminalAtCancellation=${search.terminalAtCancellation.get()}."
            }
            return search.cancellations.get()
        }
    val latestPitId: String get() = checkNotNull(latestObservedPitId.get())
    val closedPitIds: List<String> get() = observedClosedPitIds.toList()
    val heldSearchRequestCount: Long get() = probe(ElasticsearchQueryOperation.SEARCH).heldRequests.get()
    val heldSearchResponseCount: Long get() = probe(ElasticsearchQueryOperation.SEARCH).heldResponses.get()
    val heldSearchTerminalAtCancellation: Boolean?
        get() = probe(ElasticsearchQueryOperation.SEARCH).terminalAtCancellation.get()
    val heldSearchRequestPrecededCancellation: Boolean
        get() = probe(ElasticsearchQueryOperation.SEARCH).requestNanos.get() in
            1 until probe(ElasticsearchQueryOperation.SEARCH).cancellationNanos.get()
    val heldSearchUpstreamCancelReturned: Long
        get() {
            val search = probe(ElasticsearchQueryOperation.SEARCH)
            check(search.awaitUpstreamCancelReturned()) {
                "Held Elasticsearch SEARCH upstream cancellation did not return."
            }
            return search.upstreamCancelReturned.get()
        }

    fun subscriptionCount(operation: ElasticsearchQueryOperation): Long = probe(operation).subscriptions.get()

    fun cancellationCount(operation: ElasticsearchQueryOperation): Long = probe(operation).cancellations.get()

    override fun bind(context: QueryBackendResolutionContext): QueryBackend {
        check(routeReadinessVerified.get()) { "Elasticsearch TCK route readiness was not verified." }
        val delegate = if (nextHold.get() == null) preparedDelegate else lifecycleDelegate
        return delegate.bind(context, preparedMappingSnapshot.get())
    }

    fun verifyRouteReadiness(context: QueryBackendResolutionContext): Mono<Void> {
        val backend = delegate.bind(context) as ElasticsearchQueryBackend
        return backend.readiness().flatMap { readiness ->
            if (readiness == QueryBackendReadiness.Ready) {
                preparedMappingSnapshot.set((backend.mappingGuard as ElasticsearchQueryReadiness).mappingSnapshot)
                routeReadinessVerified.set(true)
                Mono.empty()
            } else Mono.error(AssertionError("Elasticsearch route is not ready: $readiness"))
        }
    }

    override fun reset() {
        searchResponseGate.reset()
        nextHold.set(null)
        operationProbes.values.forEach(ElasticsearchClientOperationProbe::reset)
        latestObservedPitId.set(null)
        observedClosedPitIds.clear()
    }

    override fun holdNextList(hold: QueryBackendClientHold) {
        val armed = ArmedSearchHold(hold, searchResponseGate.arm())
        check(nextHold.compareAndSet(null, armed)) {
            "An Elasticsearch client publisher hold is already armed."
        }
    }

    fun awaitHeldSearchRequest() {
        check(probe(ElasticsearchQueryOperation.SEARCH).awaitHeldRequest()) {
            "Held Elasticsearch SEARCH was not subscribed and requested."
        }
        check(searchResponseGate.awaitIntercepted()) {
            "Held Elasticsearch SEARCH response did not reach the HTTP interceptor."
        }
        val search = probe(ElasticsearchQueryOperation.SEARCH)
        check(search.heldResponses.get() == 0L) { "Held Elasticsearch SEARCH produced a raw response." }
        check(search.heldTerminals.get() == 0L) { "Held Elasticsearch SEARCH terminated before cancellation." }
    }

    override fun <T : Any> observe(
        context: ElasticsearchQueryOperationContext,
        publisher: Mono<T>,
    ): Mono<T> = HoldingClientMono(
        publisher,
        context,
        if (context.operation == ElasticsearchQueryOperation.SEARCH) claimSearchHold(context.queryToken) else null,
        probe(context.operation),
        observedClosedPitIds,
        searchResponseGate,
    )

    override fun updatePitId(pitId: String) {
        latestObservedPitId.set(pitId)
    }

    override fun decorateSearch(request: co.elastic.clients.elasticsearch.core.SearchRequest): co.elastic.clients.elasticsearch.core.SearchRequest {
        val armed = nextHold.get() ?: return request
        val searchOrdinal = armed.observedSearches.incrementAndGet()
        val targetOrdinal = when (armed.hold) {
            QueryBackendClientHold.BEFORE_FIRST_RESULT -> 1L
            QueryBackendClientHold.AFTER_FIRST_RESULT -> 2L
        }
        return if (searchOrdinal == targetOrdinal) {
            request.rebuild().preference(armed.queryToken).build()
        } else {
            request
        }
    }

    private fun probe(operation: ElasticsearchQueryOperation): ElasticsearchClientOperationProbe =
        operationProbes.getValue(operation)

    private fun claimSearchHold(queryToken: String?): QueryBackendClientHold? {
        val armed = nextHold.get() ?: return null
        if (queryToken != armed.queryToken || !nextHold.compareAndSet(armed, null)) {
            return null
        }
        return armed.hold
    }

    private class ArmedSearchHold(
        val hold: QueryBackendClientHold,
        val queryToken: String,
        val observedSearches: AtomicLong = AtomicLong(),
    )
}

private class PreparedPitElasticsearchQueryTransport(
    private val delegate: ElasticsearchQueryTransport,
    private val preparedPitId: AtomicReference<String>,
    private val latestObservedPitId: AtomicReference<String>,
) : ElasticsearchQueryTransport by delegate {
    override fun open(index: String): Mono<String> = Mono.defer {
        preparedPitId.getAndSet(null)?.let { pitId ->
            latestObservedPitId.set(pitId)
            Mono.just(pitId)
        } ?: delegate.open(index)
    }
}

private class HoldingClientMono<T : Any>(
    source: Mono<out T>,
    private val operationContext: ElasticsearchQueryOperationContext,
    private val hold: QueryBackendClientHold?,
    private val probe: ElasticsearchClientOperationProbe,
    private val closedPitIds: ConcurrentLinkedQueue<String>,
    private val searchResponseGate: ElasticsearchSearchResponseGate,
) : MonoOperator<T, T>(source) {
    override fun subscribe(actual: CoreSubscriber<in T>) {
        source.subscribe(
            HoldingClientSubscriber(
                actual,
                operationContext,
                hold != null,
                probe,
                closedPitIds,
                searchResponseGate,
            ),
        )
    }
}

private class HoldingClientSubscriber<T : Any>(
    private val downstream: CoreSubscriber<in T>,
    private val operationContext: ElasticsearchQueryOperationContext,
    private val held: Boolean,
    private val probe: ElasticsearchClientOperationProbe,
    private val closedPitIds: ConcurrentLinkedQueue<String>,
    private val searchResponseGate: ElasticsearchSearchResponseGate,
) : CoreSubscriber<T>, Subscription {
    private lateinit var upstream: Subscription
    private val cancelled = AtomicBoolean()
    private val terminal = AtomicBoolean()
    private val requested = AtomicBoolean()
    private val responseSeen = AtomicBoolean()

    override fun currentContext() = downstream.currentContext()
    override fun onSubscribe(subscription: Subscription) {
        upstream = subscription
        probe.subscriptions.incrementAndGet()
        if (held) probe.heldSubscriptions.incrementAndGet()
        if (operationContext.operation == ElasticsearchQueryOperation.CLOSE_PIT) {
            operationContext.pitId?.let(closedPitIds::add)
        }
        downstream.onSubscribe(this)
        if (held) requestUpstream(Long.MAX_VALUE)
    }
    override fun request(n: Long) {
        if (!held) requestUpstream(n)
    }
    override fun cancel() {
        val inFlight = !terminal.get() && !responseSeen.get() && cancelled.compareAndSet(false, true)
        if (inFlight) {
            probe.cancellations.incrementAndGet()
            if (held) {
                probe.terminalAtCancellation.set(terminal.get())
                probe.cancellationNanos.compareAndSet(0, System.nanoTime())
                probe.heldCancellationLatch.get().countDown()
            }
        }
        val releaseHeldResponse = inFlight && held
        try {
            upstream.cancel()
            if (releaseHeldResponse) {
                probe.upstreamCancelReturned.incrementAndGet()
                probe.upstreamCancelReturnedLatch.get().countDown()
            }
        } finally {
            if (releaseHeldResponse) searchResponseGate.release()
        }
    }
    override fun onNext(value: T) {
        responseSeen.set(true)
        probe.responses.incrementAndGet()
        if (held) probe.heldResponses.incrementAndGet()
        downstream.onNext(value)
    }
    override fun onError(error: Throwable) {
        terminal.set(true)
        probe.errors.incrementAndGet()
        if (held) probe.heldTerminals.incrementAndGet()
        downstream.onError(error)
    }
    override fun onComplete() {
        terminal.set(true)
        probe.completions.incrementAndGet()
        if (held) probe.heldTerminals.incrementAndGet()
        downstream.onComplete()
    }

    private fun requestUpstream(n: Long) {
        if (n <= 0) {
            upstream.request(n)
            return
        }
        if (held && requested.compareAndSet(false, true)) {
            upstream.request(n)
            probe.heldRequests.incrementAndGet()
            probe.requestNanos.compareAndSet(0, System.nanoTime())
            probe.heldRequestLatch.get().countDown()
            return
        }
        upstream.request(n)
    }
}

private class ElasticsearchClientOperationProbe {
    val subscriptions = AtomicLong()
    val heldSubscriptions = AtomicLong()
    val heldRequests = AtomicLong()
    val cancellations = AtomicLong()
    val responses = AtomicLong()
    val heldResponses = AtomicLong()
    val heldTerminals = AtomicLong()
    val completions = AtomicLong()
    val errors = AtomicLong()
    val terminalAtCancellation = AtomicReference<Boolean?>()
    val requestNanos = AtomicLong()
    val cancellationNanos = AtomicLong()
    val heldRequestLatch = AtomicReference(CountDownLatch(1))
    val heldCancellationLatch = AtomicReference(CountDownLatch(1))
    val upstreamCancelReturned = AtomicLong()
    val upstreamCancelReturnedLatch = AtomicReference(CountDownLatch(1))

    fun awaitHeldRequest(): Boolean = heldRequestLatch.get().await(2, TimeUnit.SECONDS)

    fun awaitHeldCancellation(): Boolean = heldCancellationLatch.get().await(2, TimeUnit.SECONDS)

    fun awaitUpstreamCancelReturned(): Boolean = upstreamCancelReturnedLatch.get().await(2, TimeUnit.SECONDS)

    fun reset() {
        subscriptions.set(0)
        heldSubscriptions.set(0)
        heldRequests.set(0)
        cancellations.set(0)
        responses.set(0)
        heldResponses.set(0)
        heldTerminals.set(0)
        completions.set(0)
        errors.set(0)
        terminalAtCancellation.set(null)
        requestNanos.set(0)
        cancellationNanos.set(0)
        heldRequestLatch.set(CountDownLatch(1))
        heldCancellationLatch.set(CountDownLatch(1))
        upstreamCancelReturned.set(0)
        upstreamCancelReturnedLatch.set(CountDownLatch(1))
    }
}

private fun QueryValue.toElasticsearchValue(system: Boolean): Any? = when (this) {
    is QueryValue.BooleanValue -> value
    is QueryValue.IntegerValue -> value
    is QueryValue.FloatingValue -> value
    is QueryValue.DecimalValue -> value
    is QueryValue.StringValue -> value
    is QueryValue.InstantValue -> if (system) value.toEpochMilli() else value.toString()
    is QueryValue.EnumValue -> value
    is QueryValue.ListValue -> values.map { it.toElasticsearchValue(system) }
    is QueryValue.ObjectValue -> values.mapValues { (_, value) -> value.toElasticsearchValue(system) }
    is QueryValue.BinaryValue -> Base64.getEncoder().encodeToString(value)
    QueryValue.NullValue -> null
}
