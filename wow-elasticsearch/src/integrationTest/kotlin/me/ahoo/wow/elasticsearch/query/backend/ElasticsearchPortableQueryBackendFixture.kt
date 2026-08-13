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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class ElasticsearchPortableQueryBackendFixture(
    private val client: ReactiveElasticsearchClient,
    private val documentKind: QueryDocumentKind,
) {
    private val indexName = when (documentKind) {
        QueryDocumentKind.SNAPSHOT -> PortableQueryDataset.target(documentKind).namedAggregate.toSnapshotIndexName()
        QueryDocumentKind.EVENT_STREAM -> PortableQueryDataset.target(documentKind).namedAggregate.toEventStreamIndexName()
    }
    private val prepared = AtomicBoolean()
    val backendFactory = ElasticsearchObservableQueryBackendFactory(client)

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
            .then(warmPit())
    }

    fun clear(): Mono<Void> = client.indices().delete { request -> request.index(indexName) }
        .onErrorResume { Mono.empty() }
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

    private fun warmPit(): Mono<Void> = Mono.defer {
        client.openPointInTime(
            OpenPointInTimeRequest.of { request ->
                request.index(indexName).keepAlive { keepAlive -> keepAlive.time("1m") }
            },
        ).flatMap { opened ->
            client.closePointInTime(ClosePointInTimeRequest.of { request -> request.id(opened.id()) })
        }.then()
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
) : ObservableQueryBackendFactory, ElasticsearchQueryPublisherObserver {
    private val delegate = ElasticsearchQueryBackendFactory(client)
    private val preparedDelegate = ElasticsearchQueryBackendBinder(
        client,
        ElasticsearchNativeQueryTemplateRegistry(),
        me.ahoo.wow.query.validation.QueryBudgetLimit.UNBOUNDED,
    )
    private val nextHold = AtomicReference<QueryBackendClientHold?>()
    private val operationProbes = EnumMap<ElasticsearchQueryOperation, ElasticsearchClientOperationProbe>(
        ElasticsearchQueryOperation::class.java,
    ).apply {
        ElasticsearchQueryOperation.entries.forEach { operation -> put(operation, ElasticsearchClientOperationProbe()) }
    }
    private val latestObservedPitId = AtomicReference<String>()
    private val observedClosedPitIds = ConcurrentLinkedQueue<String>()
    private val routeReadinessVerified = AtomicBoolean()
    private val preparedMappingSnapshot = AtomicReference<ElasticsearchQueryMappingSnapshot>()

    init {
        ElasticsearchQueryPublisherObservers.install(client, this)
    }

    override val subscriptionCount: Long get() = subscriptionCount(ElasticsearchQueryOperation.SEARCH)
    override val cancellationCount: Long get() = cancellationCount(ElasticsearchQueryOperation.SEARCH)
    val latestPitId: String get() = checkNotNull(latestObservedPitId.get())
    val closedPitIds: List<String> get() = observedClosedPitIds.toList()

    fun subscriptionCount(operation: ElasticsearchQueryOperation): Long = probe(operation).subscriptions.get()

    fun cancellationCount(operation: ElasticsearchQueryOperation): Long = probe(operation).cancellations.get()

    override fun bind(context: QueryBackendResolutionContext): QueryBackend {
        check(routeReadinessVerified.get()) { "Elasticsearch TCK route readiness was not verified." }
        return preparedDelegate.bind(context, preparedMappingSnapshot.get())
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
        nextHold.set(null)
        operationProbes.values.forEach(ElasticsearchClientOperationProbe::reset)
        latestObservedPitId.set(null)
        observedClosedPitIds.clear()
    }

    override fun holdNextList(hold: QueryBackendClientHold) {
        check(nextHold.compareAndSet(null, hold)) { "An Elasticsearch client publisher hold is already armed." }
    }

    override fun <T : Any> observe(
        context: ElasticsearchQueryOperationContext,
        publisher: Mono<T>,
    ): Mono<T> = HoldingClientMono(
        publisher,
        context,
        if (context.operation == ElasticsearchQueryOperation.SEARCH) nextHold.getAndSet(null) else null,
        probe(context.operation),
        observedClosedPitIds,
    )

    override fun updatePitId(pitId: String) {
        latestObservedPitId.set(pitId)
    }

    private fun probe(operation: ElasticsearchQueryOperation): ElasticsearchClientOperationProbe =
        operationProbes.getValue(operation)
}

private class HoldingClientMono<T : Any>(
    source: Mono<out T>,
    private val operationContext: ElasticsearchQueryOperationContext,
    private val hold: QueryBackendClientHold?,
    private val probe: ElasticsearchClientOperationProbe,
    private val closedPitIds: ConcurrentLinkedQueue<String>,
) : MonoOperator<T, T>(source) {
    override fun subscribe(actual: CoreSubscriber<in T>) {
        source.subscribe(HoldingClientSubscriber(actual, operationContext, hold, probe, closedPitIds))
    }
}

private class HoldingClientSubscriber<T : Any>(
    private val downstream: CoreSubscriber<in T>,
    private val operationContext: ElasticsearchQueryOperationContext,
    private val hold: QueryBackendClientHold?,
    private val probe: ElasticsearchClientOperationProbe,
    private val closedPitIds: ConcurrentLinkedQueue<String>,
) : CoreSubscriber<T>, Subscription {
    private lateinit var upstream: Subscription
    private val cancelled = AtomicBoolean()

    override fun currentContext() = downstream.currentContext()
    override fun onSubscribe(subscription: Subscription) {
        upstream = subscription
        probe.subscriptions.incrementAndGet()
        if (operationContext.operation == ElasticsearchQueryOperation.CLOSE_PIT) {
            operationContext.pitId?.let(closedPitIds::add)
        }
        downstream.onSubscribe(this)
    }
    override fun request(n: Long) {
        if (hold != QueryBackendClientHold.BEFORE_FIRST_RESULT) {
            upstream.request(n)
        }
    }
    override fun cancel() {
        if (cancelled.compareAndSet(false, true)) probe.cancellations.incrementAndGet()
        upstream.cancel()
    }
    override fun onNext(value: T) {
        probe.responses.incrementAndGet()
        if (hold != QueryBackendClientHold.BEFORE_FIRST_RESULT) downstream.onNext(value)
    }
    override fun onError(error: Throwable) {
        probe.errors.incrementAndGet()
        downstream.onError(error)
    }
    override fun onComplete() {
        probe.completions.incrementAndGet()
        if (hold == QueryBackendClientHold.AFTER_FIRST_RESULT) return
        downstream.onComplete()
    }
}

private class ElasticsearchClientOperationProbe {
    val subscriptions = AtomicLong()
    val cancellations = AtomicLong()
    val responses = AtomicLong()
    val completions = AtomicLong()
    val errors = AtomicLong()

    fun reset() {
        subscriptions.set(0)
        cancellations.set(0)
        responses.set(0)
        completions.set(0)
        errors.set(0)
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
