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

package me.ahoo.wow.mongo.query.backend

import com.mongodb.client.model.Indexes
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toSnapshotCollectionName
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import reactor.core.publisher.Flux
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.validation.QueryBudgetLimit
import me.ahoo.wow.tck.query.backend.ObservableQueryBackendFactory
import me.ahoo.wow.tck.query.backend.PortableQueryDataset
import me.ahoo.wow.tck.query.backend.QueryBackendClientHold
import org.bson.Document
import org.bson.types.Decimal128
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class MongoPortableQueryBackendFixture(
    private val database: MongoDatabase,
    private val documentKind: QueryDocumentKind
) {
    private val prepared = AtomicBoolean()
    private val collectionName = when (documentKind) {
        QueryDocumentKind.SNAPSHOT -> PortableQueryDataset.target(documentKind).namedAggregate.toSnapshotCollectionName()
        QueryDocumentKind.EVENT_STREAM ->
            PortableQueryDataset.target(documentKind).namedAggregate.toEventStreamCollectionName()
    }

    val backendFactory = MongoObservableQueryBackendFactory(database)

    fun initializeCollection() {
        val documents = documents(PortableQueryDataset)
        StepVerifier.create(
            Mono.from(database.createCollection(collectionName))
                .then(Mono.from(collection().createIndex(Indexes.text(PortableQueryDataset.TITLE.value))))
                .then(Mono.from(collection().insertMany(documents)))
        ).expectNextCount(1).verifyComplete()
        prepared.set(true)
        backendFactory.verifyRouteReadiness(
            QueryBackendResolutionContext(
                PortableQueryDataset.target(documentKind),
                PortableQueryDataset.schema(documentKind),
                PortableQueryDataset.vectors.first().expression
            )
        )
    }

    fun prepare(dataset: PortableQueryDataset): Mono<Void> {
        if (prepared.get()) {
            return Mono.empty()
        }
        return Mono.from(collection().insertMany(documents(dataset)))
            .doOnSuccess { prepared.set(true) }
            .then()
    }

    fun clear(): Mono<Void> = Mono.from(collection().deleteMany(Document()))
        .doOnSuccess { prepared.set(false) }
        .then()

    private fun documents(dataset: PortableQueryDataset): List<Document> =
        dataset.storedDocuments(documentKind).map { stored ->
            val schema = dataset.schema(documentKind)
            Document().also { target ->
                stored.fields.forEach { (field, queryValue) ->
                    if ('.' !in field.value) {
                        val physicalField = when {
                            documentKind == QueryDocumentKind.SNAPSHOT && field.value == "aggregateId" -> "_id"
                            documentKind == QueryDocumentKind.EVENT_STREAM && field.value == "id" -> "_id"
                            else -> field.value
                        }
                        target[physicalField] = queryValue.toMongoValue(schema.fields.getValue(field).system)
                    }
                }
                if (documentKind == QueryDocumentKind.EVENT_STREAM) {
                    val body = Document()
                    stored.fields.forEach { (field, queryValue) ->
                        if (field.value.startsWith("body.")) {
                            body[field.value.removePrefix("body.")] =
                                queryValue.toMongoValue(schema.fields.getValue(field).system)
                        }
                    }
                    if (body.isNotEmpty()) {
                        target["body"] = listOf(body)
                    }
                }
            }
        }

    private fun collection() = database.getCollection(collectionName)
}

internal class MongoObservableQueryBackendFactory(
    database: MongoDatabase,
    maxBudget: QueryBudgetLimit = QueryBudgetLimit.UNBOUNDED,
    private val beforeUpstreamCancel: () -> Unit = {}
) : ObservableQueryBackendFactory, MongoQueryPublisherObserver {
    private val nextHold = AtomicReference<QueryBackendClientHold?>()
    private val subscriptions = AtomicLong()
    private val cancellations = AtomicLong()
    private val delegate = MongoQueryBackendFactory(database, maxBudget = maxBudget)
    private val routeReadinessVerified = AtomicBoolean()

    init {
        MongoQueryPublisherObservers.install(database, this)
    }

    override val subscriptionCount: Long
        get() = subscriptions.get()

    override val cancellationCount: Long
        get() = cancellations.get()

    override fun bind(context: QueryBackendResolutionContext): QueryBackend {
        check(routeReadinessVerified.get()) { "Mongo TCK route readiness was not verified against the real backend." }
        return ReadySnapshotQueryBackend(delegate.bind(context))
    }

    fun verifyRouteReadiness(context: QueryBackendResolutionContext) {
        StepVerifier.create(delegate.bind(context).readiness())
            .expectNext(QueryBackendReadiness.Ready)
            .verifyComplete()
        routeReadinessVerified.set(true)
    }

    override fun reset() {
        nextHold.set(null)
        subscriptions.set(0)
        cancellations.set(0)
    }

    override fun holdNextList(hold: QueryBackendClientHold) {
        check(nextHold.compareAndSet(null, hold)) { "A Mongo client publisher hold is already armed." }
    }

    override fun <T : Any> observe(publisher: Publisher<T>): Publisher<T> {
        val hold = nextHold.getAndSet(null)
        return HoldingMongoPublisher(publisher, hold, subscriptions, cancellations, beforeUpstreamCancel)
    }
}

private class ReadySnapshotQueryBackend(
    private val delegate: QueryBackend
) : QueryBackend by delegate {
    override fun readiness(): Mono<QueryBackendReadiness> = Mono.just(QueryBackendReadiness.Ready)
}

private class HoldingMongoPublisher<T : Any>(
    private val source: Publisher<T>,
    private val hold: QueryBackendClientHold?,
    private val subscriptions: AtomicLong,
    private val cancellations: AtomicLong,
    private val beforeUpstreamCancel: () -> Unit
) : Publisher<T> {
    override fun subscribe(subscriber: Subscriber<in T>) {
        val bridge = HoldingMongoSubscriber(
            subscriber,
            hold,
            subscriptions,
            cancellations,
            beforeUpstreamCancel
        )
        subscriber.onSubscribe(bridge)
        source.subscribe(bridge)
    }
}

private class HoldingMongoSubscriber<T : Any>(
    private val downstream: Subscriber<in T>,
    private val hold: QueryBackendClientHold?,
    private val subscriptions: AtomicLong,
    private val cancellations: AtomicLong,
    private val beforeUpstreamCancel: () -> Unit
) : Subscriber<T>, Subscription {
    private val upstream = AtomicReference<Subscription?>()
    private val requested = AtomicBoolean()
    private val pendingDemand = AtomicLong()
    private val cancelled = AtomicBoolean()
    private val upstreamCancelled = AtomicBoolean()
    private val delivered = AtomicBoolean()

    override fun request(amount: Long) {
        if (amount <= 0) {
            cancel()
            downstream.onError(IllegalArgumentException("Reactive Streams demand must be positive."))
            return
        }
        if (hold == QueryBackendClientHold.AFTER_FIRST_RESULT && requested.compareAndSet(false, true)) {
            upstream.get()?.request(1)
        } else if (hold == null) {
            pendingDemand.updateAndGet { pending ->
                if (Long.MAX_VALUE - pending < amount) Long.MAX_VALUE else pending + amount
            }
            upstream.get()?.let(::drainDemand)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        upstream.get()?.let(::cancelUpstream)
    }

    override fun onSubscribe(subscription: Subscription) {
        if (!upstream.compareAndSet(null, subscription)) {
            subscription.cancel()
            return
        }
        subscriptions.incrementAndGet()
        when {
            cancelled.get() -> cancelUpstream(subscription)
            hold == QueryBackendClientHold.AFTER_FIRST_RESULT && requested.get() -> subscription.request(1)
            hold == null -> drainDemand(subscription)
        }
    }

    override fun onNext(value: T) {
        if (!cancelled.get() && (hold == null || delivered.compareAndSet(false, true))) {
            downstream.onNext(value)
        }
    }

    override fun onError(error: Throwable) {
        if (!cancelled.get()) {
            downstream.onError(error)
        }
    }

    override fun onComplete() {
        if (!cancelled.get()) {
            downstream.onComplete()
        }
    }

    private fun cancelUpstream(subscription: Subscription) {
        if (upstreamCancelled.compareAndSet(false, true)) {
            beforeUpstreamCancel()
            cancellations.incrementAndGet()
            subscription.cancel()
        }
    }

    private fun drainDemand(subscription: Subscription) {
        pendingDemand.getAndSet(0).takeIf { it > 0 }?.let(subscription::request)
    }
}

private fun QueryValue.toMongoValue(system: Boolean): Any? = when (this) {
    is QueryValue.BooleanValue -> value
    is QueryValue.IntegerValue -> value
    is QueryValue.FloatingValue -> value
    is QueryValue.DecimalValue -> Decimal128(value)
    is QueryValue.StringValue -> value
    is QueryValue.InstantValue -> if (system) value.toEpochMilli() else value.toString()
    is QueryValue.EnumValue -> value
    is QueryValue.ListValue -> values.map { value -> value.toMongoValue(system) }
    is QueryValue.ObjectValue -> Document(values.mapValues { (_, value) -> value.toMongoValue(system) })
    is QueryValue.BinaryValue -> value.copyOf()
    QueryValue.NullValue -> null
}
