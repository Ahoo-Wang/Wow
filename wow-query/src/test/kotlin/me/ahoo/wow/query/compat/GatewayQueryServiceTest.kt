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

package me.ahoo.wow.query.compat

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
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
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.toNamedAggregate
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.event.GatewayEventStreamQueryService
import me.ahoo.wow.query.event.GatewayEventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.GatewaySnapshotQueryService
import me.ahoo.wow.query.snapshot.GatewaySnapshotQueryServiceFactory
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toLinkedHashMap
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class GatewayQueryServiceTest {
    private val projection = Projection(include = listOf("aggregateId", "state.id"))
    private val snapshot = MaterializedSnapshot(
        contextName = MOCK_AGGREGATE_METADATA.contextName,
        aggregateName = MOCK_AGGREGATE_METADATA.aggregateName,
        tenantId = TenantId.DEFAULT_TENANT_ID,
        aggregateId = "aggregate-id",
        version = 1,
        eventId = "event-id",
        firstOperator = "operator",
        operator = "operator",
        firstEventTime = 1,
        eventTime = 2,
        state = MockStateAggregate("aggregate-id"),
        snapshotTime = 3,
        deleted = false
    )
    private val eventStream = generateEventStream(MOCK_AGGREGATE_METADATA.aggregateId("aggregate-id"), eventCount = 1)
    private val snapshotDocument = document(snapshot)
    private val eventDocument = document(eventStream)

    @Test
    fun `facades adapt only backend canonical system time values without mutating source documents`() {
        val backendSnapshot = snapshotDocument.withValues(
            "firstEventTime" to Instant.ofEpochMilli(snapshot.firstEventTime),
            "snapshotTime" to Instant.ofEpochMilli(snapshot.snapshotTime),
        )
        val backendEvent = eventDocument.withValues(
            "createTime" to Instant.ofEpochMilli(eventStream.createTime),
        )

        StepVerifier.create(
            GatewaySnapshotQueryService<MockStateAggregate>(
                MOCK_AGGREGATE_METADATA,
                RecordingLegacyGateway(backendSnapshot),
            ).single(SingleQuery(Condition.ALL)),
        ).expectNext(snapshot).verifyComplete()
        StepVerifier.create(
            GatewayEventStreamQueryService(
                MOCK_AGGREGATE_METADATA,
                RecordingLegacyGateway(backendEvent),
            ).single(SingleQuery(Condition.ALL)),
        ).expectNext(eventStream).verifyComplete()

        backendSnapshot["firstEventTime"].assert().isEqualTo(Instant.ofEpochMilli(snapshot.firstEventTime))
        backendSnapshot["eventTime"].assert().isEqualTo(snapshot.eventTime)
        backendSnapshot["snapshotTime"].assert().isEqualTo(Instant.ofEpochMilli(snapshot.snapshotTime))
        backendEvent["createTime"].assert().isEqualTo(Instant.ofEpochMilli(eventStream.createTime))
    }

    @Test
    fun `all dynamic facade paths expose legacy epoch millis without mutating canonical documents`() {
        val applicationInstant = Instant.parse("2026-08-14T00:00:00Z")
        val backendSnapshot = snapshotDocument.withValues(
            "firstEventTime" to Instant.ofEpochMilli(snapshot.firstEventTime),
            "eventTime" to Instant.ofEpochMilli(snapshot.eventTime),
            "snapshotTime" to Instant.ofEpochMilli(snapshot.snapshotTime),
            "applicationInstant" to applicationInstant,
        )
        val backendEvent = eventDocument.withValues(
            "createTime" to Instant.ofEpochMilli(eventStream.createTime),
            "applicationInstant" to applicationInstant,
        )
        val snapshotService = GatewaySnapshotQueryService<MockStateAggregate>(
            MOCK_AGGREGATE_METADATA,
            RecordingLegacyGateway(backendSnapshot),
        )
        val eventService = GatewayEventStreamQueryService(
            MOCK_AGGREGATE_METADATA,
            RecordingLegacyGateway(backendEvent),
        )

        listOf(
            snapshotService.dynamicSingle(SingleQuery(Condition.ALL)),
            snapshotService.dynamicList(ListQuery(Condition.ALL)).single(),
            snapshotService.dynamicPaged(PagedQuery(Condition.ALL)).map { it.list.single() },
        ).forEach { result ->
            StepVerifier.create(result).assertNext { document ->
                document.assertLegacyTimes(
                    "firstEventTime" to snapshot.firstEventTime,
                    "eventTime" to snapshot.eventTime,
                    "snapshotTime" to snapshot.snapshotTime,
                )
                document["applicationInstant"].assert().isEqualTo(applicationInstant)
                JsonSerializer.writeValueAsString(document).let { json ->
                    json.contains("\"firstEventTime\":${snapshot.firstEventTime}").assert().isTrue()
                    json.contains("\"snapshotTime\":${snapshot.snapshotTime}").assert().isTrue()
                    json.contains(applicationInstant.toString()).assert().isTrue()
                }
            }.verifyComplete()
        }
        listOf(
            eventService.dynamicSingle(SingleQuery(Condition.ALL)),
            eventService.dynamicList(ListQuery(Condition.ALL)).single(),
            eventService.dynamicPaged(PagedQuery(Condition.ALL)).map { it.list.single() },
        ).forEach { result ->
            StepVerifier.create(result).assertNext { document ->
                document.assertLegacyTimes("createTime" to eventStream.createTime)
                document["applicationInstant"].assert().isEqualTo(applicationInstant)
                JsonSerializer.writeValueAsString(document)
                    .contains("\"createTime\":${eventStream.createTime}").assert().isTrue()
            }.verifyComplete()
        }

        backendSnapshot["firstEventTime"].assert().isEqualTo(Instant.ofEpochMilli(snapshot.firstEventTime))
        backendSnapshot["eventTime"].assert().isEqualTo(Instant.ofEpochMilli(snapshot.eventTime))
        backendSnapshot["snapshotTime"].assert().isEqualTo(Instant.ofEpochMilli(snapshot.snapshotTime))
        backendEvent["createTime"].assert().isEqualTo(Instant.ofEpochMilli(eventStream.createTime))
    }

    @Test
    fun `legacy time adaptation does not coerce non-time instants and keeps malformed times invalid`() {
        val nonTimeInstant = snapshotDocument.withValues(
            "contextName" to Instant.EPOCH,
        )
        val malformedTime = snapshotDocument.withValues(
            "snapshotTime" to mapOf("sensitive" to "must-not-leak"),
        )

        StepVerifier.create(
            GatewaySnapshotQueryService<MockStateAggregate>(
                MOCK_AGGREGATE_METADATA,
                RecordingLegacyGateway(nonTimeInstant),
            ).single(SingleQuery(Condition.ALL)),
        ).assertNext { result -> result.contextName.assert().isEqualTo(Instant.EPOCH.toString()) }.verifyComplete()
        StepVerifier.create(
            GatewaySnapshotQueryService<MockStateAggregate>(
                MOCK_AGGREGATE_METADATA,
                RecordingLegacyGateway(malformedTime),
            ).single(SingleQuery(Condition.ALL)),
        ).expectErrorSatisfies(::assertResultInvalid).verify()
    }

    @Test
    fun `all dynamic facade paths reject malformed fixed system times without leaking values`() {
        val malformedSnapshot = snapshotDocument.withValues(
            "snapshotTime" to mapOf("sensitive" to "must-not-leak"),
        )
        val malformedEvent = eventDocument.withValues(
            "createTime" to mapOf("sensitive" to "must-not-leak"),
        )
        val snapshotService = GatewaySnapshotQueryService<MockStateAggregate>(
            MOCK_AGGREGATE_METADATA,
            RecordingLegacyGateway(malformedSnapshot),
        )
        val eventService = GatewayEventStreamQueryService(
            MOCK_AGGREGATE_METADATA,
            RecordingLegacyGateway(malformedEvent),
        )

        listOf(
            snapshotService.dynamicSingle(SingleQuery(Condition.ALL)),
            snapshotService.dynamicList(ListQuery(Condition.ALL)).single(),
            snapshotService.dynamicPaged(PagedQuery(Condition.ALL)).flatMap { Mono.just(it.list.single()) },
            eventService.dynamicSingle(SingleQuery(Condition.ALL)),
            eventService.dynamicList(ListQuery(Condition.ALL)).single(),
            eventService.dynamicPaged(PagedQuery(Condition.ALL)).flatMap { Mono.just(it.list.single()) },
        ).forEach { publisher ->
            StepVerifier.create(publisher).expectErrorSatisfies(::assertResultInvalid).verify()
        }
    }

    @Test
    fun `dynamic time adapter preserves projected absence and nullable values`() {
        val partialSnapshot = ImmutableDynamicDocument.copyOf(mapOf("aggregateId" to snapshot.aggregateId))
        val nullableEvent = ImmutableDynamicDocument.copyOf(mapOf("id" to eventStream.id, "createTime" to null))

        StepVerifier.create(
            GatewaySnapshotQueryService<MockStateAggregate>(
                MOCK_AGGREGATE_METADATA,
                RecordingLegacyGateway(partialSnapshot),
            ).dynamicSingle(SingleQuery(Condition.ALL)),
        ).expectNext(partialSnapshot).verifyComplete()
        StepVerifier.create(
            GatewayEventStreamQueryService(
                MOCK_AGGREGATE_METADATA,
                RecordingLegacyGateway(nullableEvent),
            ).dynamicSingle(SingleQuery(Condition.ALL)),
        ).expectNext(nullableEvent).verifyComplete()
    }

    @Test
    fun `snapshot facade delegates all seven methods once and materializes typed results after gateway`() {
        val gateway = RecordingLegacyGateway(snapshotDocument)
        val service = GatewaySnapshotQueryService<MockStateAggregate>(MOCK_AGGREGATE_METADATA, gateway)
        val single = SingleQuery(Condition.ALL, projection)
        val list = ListQuery(Condition.ALL, projection, limit = 2)
        val page = PagedQuery(Condition.ALL, projection, pagination = Pagination(2, 2))

        StepVerifier.create(service.single(single)).assertNext { it.assert().isEqualTo(snapshot) }.verifyComplete()
        StepVerifier.create(
            service.dynamicSingle(single)
        ).assertNext { it.assert().isSameAs(snapshotDocument) }.verifyComplete()
        StepVerifier.create(service.list(list)).expectNext(snapshot).verifyComplete()
        StepVerifier.create(service.dynamicList(list)).expectNext(snapshotDocument).verifyComplete()
        StepVerifier.create(service.paged(page)).assertNext { result ->
            result.total.assert().isEqualTo(1)
            result.list.assert().containsExactly(snapshot)
        }.verifyComplete()
        StepVerifier.create(service.dynamicPaged(page)).assertNext { result ->
            result.list.single().assert().isSameAs(snapshotDocument)
        }.verifyComplete()
        StepVerifier.create(service.count(Condition.ALL)).expectNext(1).verifyComplete()

        gateway.calls.get().assert().isEqualTo(7)
        gateway.requests.map { it.target.documentKind }.assert().containsOnly(QueryDocumentKind.SNAPSHOT)
        gateway.requests.filterIsInstance<ListQueryRequest<*>>().map { it.limit }.assert().containsOnly(2)
        gateway.requests.filterIsInstance<PageQueryRequest<*>>().first().page.index.assert().isEqualTo(2)
    }

    @Test
    fun `event facade preserves empty single and partial gateway errors`() {
        val partialError = QueryException(
            QueryErrorCode.BACKEND_FAILURE,
            QueryStage.EXECUTION,
            QueryErrorReason.BACKEND_EXECUTION_FAILED
        )
        val gateway = RecordingLegacyGateway(
            document = eventDocument,
            singleResult = Mono.empty(),
            listResult = Flux.concat(Flux.just(eventDocument), Flux.error(partialError))
        )
        val service = GatewayEventStreamQueryService(MOCK_AGGREGATE_METADATA, gateway)
        val query = SingleQuery(Condition.ALL, Projection.ALL)

        StepVerifier.create(service.single(query)).verifyComplete()
        StepVerifier.create(service.dynamicSingle(query)).verifyComplete()
        StepVerifier.create(service.list(ListQuery(Condition.ALL)))
            .expectNext(eventStream)
            .expectErrorSatisfies { it.assert().isSameAs(partialError) }
            .verify()
        StepVerifier.create(service.dynamicList(ListQuery(Condition.ALL)))
            .expectNext(eventDocument)
            .expectErrorSatisfies { it.assert().isSameAs(partialError) }
            .verify()

        gateway.calls.get().assert().isEqualTo(4)
        gateway.requests.map { it.target.documentKind }.assert().containsOnly(QueryDocumentKind.EVENT_STREAM)
    }

    @Test
    fun `event facade delegates all seven methods with typed page and exact targets`() {
        val gateway = RecordingLegacyGateway(eventDocument)
        val service = GatewayEventStreamQueryService(MOCK_AGGREGATE_METADATA, gateway)
        val single = SingleQuery(Condition.ALL, projection)
        val list = ListQuery(Condition.ALL, projection, limit = 3)
        val page = PagedQuery(Condition.ALL, projection, pagination = Pagination(4, 3))

        StepVerifier.create(service.single(single)).expectNext(eventStream).verifyComplete()
        StepVerifier.create(service.dynamicSingle(single)).expectNext(eventDocument).verifyComplete()
        StepVerifier.create(service.list(list)).expectNext(eventStream).verifyComplete()
        StepVerifier.create(service.dynamicList(list)).expectNext(eventDocument).verifyComplete()
        StepVerifier.create(service.paged(page)).assertNext { result ->
            result.total.assert().isEqualTo(1)
            result.list.assert().containsExactly(eventStream)
        }.verifyComplete()
        StepVerifier.create(service.dynamicPaged(page)).assertNext { result ->
            result.list.single().assert().isSameAs(eventDocument)
        }.verifyComplete()
        StepVerifier.create(service.count(Condition.ALL)).expectNext(1).verifyComplete()

        gateway.calls.get().assert().isEqualTo(7)
        gateway.requests.map { it.target.documentKind }.assert().containsOnly(QueryDocumentKind.EVENT_STREAM)
        gateway.requests.filterIsInstance<ListQueryRequest<*>>().map { it.limit }.assert().containsOnly(3)
        gateway.requests.filterIsInstance<PageQueryRequest<*>>().map { it.page.index }.assert().containsOnly(4)
    }

    @Test
    fun `mixed projection fails reactively without calling gateway`() {
        val gateway = RecordingLegacyGateway(snapshotDocument)
        val service = GatewaySnapshotQueryService<MockStateAggregate>(MOCK_AGGREGATE_METADATA, gateway)
        val result = service.dynamicSingle(
            SingleQuery(
                Condition.ALL,
                Projection(include = listOf("state.id"), exclude = listOf("eventId"))
            )
        )

        gateway.calls.get().assert().isZero()
        StepVerifier.create(result).expectErrorSatisfies { error ->
            (error as QueryException).code.assert().isEqualTo(QueryErrorCode.INVALID_QUERY)
        }.verify()
        gateway.calls.get().assert().isZero()
    }

    @Test
    fun `all seven facade methods are cold and factories cache materialized aggregate identity`() {
        val gateway = RecordingLegacyGateway(snapshotDocument)
        val snapshotFactory = GatewaySnapshotQueryServiceFactory(gateway)
        val service = snapshotFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA)
        val single = SingleQuery(Condition.ALL)
        val list = ListQuery(Condition.ALL)
        val page = PagedQuery(Condition.ALL)
        val publishers = listOf(
            service.single(single),
            service.dynamicSingle(single),
            service.list(list),
            service.dynamicList(list),
            service.paged(page),
            service.dynamicPaged(page),
            service.count(Condition.ALL)
        )

        verifyCold(publishers, gateway)
        gateway.calls.get().assert().isEqualTo(14)
        snapshotFactory.create<MockStateAggregate>(MOCK_AGGREGATE_METADATA).assert().isSameAs(service)

        val eventGateway = RecordingLegacyGateway(eventDocument)
        val eventFactory = GatewayEventStreamQueryServiceFactory(eventGateway)
        val eventService = eventFactory.create(MOCK_AGGREGATE_METADATA)
        val eventPublishers = listOf(
            eventService.single(single),
            eventService.dynamicSingle(single),
            eventService.list(list),
            eventService.dynamicList(list),
            eventService.paged(page),
            eventService.dynamicPaged(page),
            eventService.count(Condition.ALL)
        )
        verifyCold(eventPublishers, eventGateway)
        eventGateway.calls.get().assert().isEqualTo(14)
        eventFactory.create(MOCK_AGGREGATE_METADATA).assert()
            .isSameAs(eventService)
    }

    @Test
    fun `typed materialization failure is a stable low information result error`() {
        val malformed = ImmutableDynamicDocument.copyOf(mapOf("sensitive" to "must-not-leak"))
        val gateway = RecordingLegacyGateway(malformed)
        val service = GatewaySnapshotQueryService<MockStateAggregate>(MOCK_AGGREGATE_METADATA, gateway)

        StepVerifier.create(service.single(SingleQuery(Condition.ALL))).expectErrorSatisfies { error ->
            (error as QueryException).apply {
                code.assert().isEqualTo(QueryErrorCode.RESULT_VALIDATION_FAILED)
                stage.assert().isEqualTo(QueryStage.EXECUTION)
                reason.assert().isEqualTo(QueryErrorReason.RESULT_INVALID)
                message.orEmpty().contains("sensitive").assert().isFalse()
            }
        }.verify()
    }

    @Test
    fun `typed list mapper failure before first item remains result validation failed`() {
        val malformed = ImmutableDynamicDocument.copyOf(mapOf("sensitive" to "must-not-leak"))

        StepVerifier.create(
            GatewaySnapshotQueryService<MockStateAggregate>(
                MOCK_AGGREGATE_METADATA,
                RecordingLegacyGateway(snapshotDocument, listResult = Flux.just(malformed))
            ).list(ListQuery(Condition.ALL))
        ).expectErrorSatisfies(::assertResultInvalid).verify()
        StepVerifier.create(
            GatewayEventStreamQueryService(
                MOCK_AGGREGATE_METADATA,
                RecordingLegacyGateway(eventDocument, listResult = Flux.just(malformed))
            ).list(ListQuery(Condition.ALL))
        ).expectErrorSatisfies(::assertResultInvalid).verify()
    }

    @Test
    fun `typed list mapper failure after first item reports incomplete result with validation cause`() {
        val malformed = ImmutableDynamicDocument.copyOf(mapOf("sensitive" to "must-not-leak"))
        val snapshotCancellations = AtomicInteger()
        val eventCancellations = AtomicInteger()

        StepVerifier.create(
            GatewaySnapshotQueryService<MockStateAggregate>(
                MOCK_AGGREGATE_METADATA,
                RecordingLegacyGateway(
                    snapshotDocument,
                    listResult = Flux.just(
                        snapshotDocument,
                        malformed
                    ).doOnCancel(
                        snapshotCancellations::incrementAndGet
                    )
                )
            ).list(ListQuery(Condition.ALL))
        ).expectNext(snapshot).expectErrorSatisfies(::assertIncompleteResult).verify()
        snapshotCancellations.get().assert().isEqualTo(1)
        StepVerifier.create(
            GatewayEventStreamQueryService(
                MOCK_AGGREGATE_METADATA,
                RecordingLegacyGateway(
                    eventDocument,
                    listResult = Flux.just(
                        eventDocument,
                        malformed
                    ).doOnCancel(
                        eventCancellations::incrementAndGet
                    )
                )
            ).list(ListQuery(Condition.ALL))
        ).expectNext(eventStream).expectErrorSatisfies(::assertIncompleteResult).verify()
        eventCancellations.get().assert().isEqualTo(1)
    }

    @Test
    fun `snapshot metadata failure is deferred to subscription and mapped as result invalid`() {
        val gateway = RecordingLegacyGateway(snapshotDocument)
        val service = GatewaySnapshotQueryService<MockStateAggregate>(
            "missing.aggregate".toNamedAggregate(),
            gateway
        )
        val result = service.single(SingleQuery(Condition.ALL))

        gateway.calls.get().assert().isZero()
        StepVerifier.create(result).expectErrorSatisfies { error ->
            (error as QueryException).apply {
                code.assert().isEqualTo(QueryErrorCode.RESULT_VALIDATION_FAILED)
                stage.assert().isEqualTo(QueryStage.EXECUTION)
                reason.assert().isEqualTo(QueryErrorReason.RESULT_INVALID)
            }
        }.verify()
        gateway.calls.get().assert().isEqualTo(1)
    }

    private fun document(value: Any): DynamicDocument = ImmutableDynamicDocument.copyOf(value.toLinkedHashMap())

    private fun DynamicDocument.withValues(vararg values: Pair<String, Any?>): DynamicDocument =
        ImmutableDynamicDocument.copyOf(LinkedHashMap(this).apply { putAll(values) })

    private fun DynamicDocument.assertLegacyTimes(vararg expected: Pair<String, Long>) {
        expected.forEach { (field, value) ->
            this[field].assert().isInstanceOf(Long::class.javaObjectType).isEqualTo(value)
        }
    }

    private fun verifyCold(publishers: List<Publisher<*>>, gateway: RecordingLegacyGateway) {
        gateway.calls.get().assert().isZero()
        publishers.forEach { publisher ->
            val before = gateway.calls.get()
            StepVerifier.create(publisher).expectNextCount(1).verifyComplete()
            StepVerifier.create(publisher).expectNextCount(1).verifyComplete()
            gateway.calls.get().assert().isEqualTo(before + 2)
        }
    }

    private fun assertResultInvalid(error: Throwable) {
        (error as QueryException).apply {
            code.assert().isEqualTo(QueryErrorCode.RESULT_VALIDATION_FAILED)
            stage.assert().isEqualTo(QueryStage.EXECUTION)
            reason.assert().isEqualTo(QueryErrorReason.RESULT_INVALID)
            causeCode.assert().isNull()
            message.orEmpty().contains("sensitive").assert().isFalse()
        }
    }

    private fun assertIncompleteResult(error: Throwable) {
        (error as QueryException).apply {
            code.assert().isEqualTo(QueryErrorCode.INCOMPLETE_RESULT)
            stage.assert().isEqualTo(QueryStage.EXECUTION)
            reason.assert().isEqualTo(QueryErrorReason.INCOMPLETE_STREAM)
            causeCode.assert().isEqualTo(QueryErrorCode.RESULT_VALIDATION_FAILED)
            message.orEmpty().contains("sensitive").assert().isFalse()
        }
    }
}

private class RecordingLegacyGateway(
    private val document: DynamicDocument,
    private val singleResult: Mono<DynamicDocument> = Mono.just(document),
    private val listResult: Flux<DynamicDocument> = Flux.just(document)
) : QueryGateway {
    val calls = AtomicInteger()
    val requests = CopyOnWriteArrayList<QueryRequest>()

    override fun <R : Any> single(request: SingleQueryRequest<R>): Mono<R> = Mono.defer {
        record(request)
        @Suppress("UNCHECKED_CAST")
        singleResult as Mono<R>
    }

    override fun <R : Any> list(request: ListQueryRequest<R>): Flux<R> = Flux.defer {
        record(request)
        @Suppress("UNCHECKED_CAST")
        listResult as Flux<R>
    }

    override fun <R : Any> page(request: PageQueryRequest<R>): Mono<QueryPage<R>> = Mono.defer {
        record(request)
        @Suppress("UNCHECKED_CAST")
        Mono.just(QueryPage(listOf(document as R), 1, QueryConsistency.EXACT))
    }

    override fun count(request: CountQueryRequest): Mono<Long> = Mono.fromSupplier {
        record(request)
        1
    }

    private fun record(request: QueryRequest) {
        calls.incrementAndGet()
        requests += request
    }
}
