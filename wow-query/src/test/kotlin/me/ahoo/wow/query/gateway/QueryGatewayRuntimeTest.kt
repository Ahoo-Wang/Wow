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

package me.ahoo.wow.query.gateway

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SimpleDynamicDocument
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

@OptIn(ExperimentalQueryGatewayApi::class)
class QueryGatewayRuntimeTest {
    @Test
    fun `gateway should remain cold and materialize an independent projected document`() {
        val authorityCalls = AtomicInteger()
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val sourceState = linkedMapOf<String, Any?>(
            "status" to "PAID",
            "bytes" to byteArrayOf(1, 2),
            "internal" to "must-not-leak",
        )
        raw.singleResult = SimpleDynamicDocument(
            linkedMapOf(
                "aggregateId" to "order-1",
                "state" to sourceState,
                "backendOnly" to "must-not-leak",
            ),
        )
        val gateway = gateway(raw) {
            authorityCalls.incrementAndGet()
            Mono.just(QueryAuthority.System("test", "gateway-test"))
        }
        val publisher = gateway.single(
            snapshotCall,
            SingleQuery(
                Condition.eq("state.status", "PAID"),
                Projection(include = listOf("state.status", "state.bytes")),
            ),
        )

        authorityCalls.get().assert().isZero()
        raw.singleCalls.get().assert().isZero()

        val first = checkNotNull(publisher.block())
        authorityCalls.get().assert().isEqualTo(1)
        raw.singleCalls.get().assert().isEqualTo(1)
        raw.lastSingleQuery!!.projection.include.assert().containsExactly("state.status", "state.bytes", "aggregateId")
        first.containsKey("aggregateId").assert().isFalse()
        first.containsKey("backendOnly").assert().isFalse()
        first.getNestedDocument("state").getValue<String>("status").assert().isEqualTo("PAID")
        first.getNestedDocument("state").containsKey("internal").assert().isFalse()

        sourceState["status"] = "SHIPPED"
        (sourceState["bytes"] as ByteArray)[0] = 9
        first.getNestedDocument("state").getValue<String>("status").assert().isEqualTo("PAID")
        first.getNestedDocument("state").getValue<ByteArray>("bytes").contentEquals(byteArrayOf(1, 2)).assert().isTrue()

        val second = checkNotNull(publisher.block())
        second.assert().isNotSameAs(first)
        authorityCalls.get().assert().isEqualTo(2)
        raw.singleCalls.get().assert().isEqualTo(2)
    }

    @Test
    fun `subject scope should become a mandatory condition before raw storage`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        raw.countResult = 3
        val gateway = gateway(raw) {
            Mono.just(
                QueryAuthority.Subject(
                    subjectId = "subject-1",
                    tenantId = "tenant-1",
                    ownerGrant = QueryOwnerGrant.Only("owner-1"),
                    spaceGrant = QuerySpaceGrant.AllowList(listOf("space-1", "space-2")),
                ),
            )
        }

        gateway.count(snapshotCall, Condition.eq("state.status", "PAID")).block().assert().isEqualTo(3)

        val condition = checkNotNull(raw.lastCountCondition)
        condition.operator.assert().isEqualTo(Operator.AND)
        condition.children.first().operator.assert().isEqualTo(Operator.DELETED)
        condition.operators().assert().contains(Operator.TENANT_ID)
            .contains(Operator.OWNER_ID)
            .contains(Operator.IN)
        condition.flatten().single { it.operator == Operator.IN }.field.assert().isEqualTo("spaceId")
        raw.countCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `missing authority should map to a public typed error without touching storage`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val gateway = gateway(raw) { Mono.empty() }

        assertThrownBy<QueryExecutionException> {
            gateway.count(snapshotCall, Condition.ALL).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.ACCESS_DENIED)
                error.path.assert().isEqualTo("$.executionContext.authority")
                error.code.assert().isEqualTo("AUTHORITY_REQUIRED")
            },
        )
        raw.countCalls.get().assert().isZero()
    }

    @Test
    fun `normalized match-none should short-circuit every legacy operation`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val gateway = gateway(raw) { Mono.just(QueryAuthority.System("test", "match-none")) }

        gateway.count(snapshotCall, Condition.ids(emptyList())).block().assert().isZero()
        raw.countCalls.get().assert().isZero()
    }

    @Test
    fun `event stream queries should not receive snapshot deletion semantics`() {
        val snapshot = ProbeSnapshotQueryService(namedAggregate)
        val event = ProbeEventStreamQueryService(namedAggregate)
        event.countResult = 2
        val gateway = gateway(snapshot, event) { Mono.just(QueryAuthority.System("test", "event-test")) }

        gateway.count(eventCall, Condition.ALL).block().assert().isEqualTo(2)

        event.lastCountCondition!!.operator.assert().isEqualTo(Operator.ALL)
        event.countCalls.get().assert().isEqualTo(1)
    }

    @Test
    fun `strict page should lower the planner identity tie-breaker`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val gateway = gateway(
            raw = raw,
            configuration = QueryGatewayConfiguration(
                executionMode = QueryExecutionMode.LEGACY,
                validationMode = QueryValidationMode.STRICT,
            ),
        ) { Mono.just(QueryAuthority.System("test", "strict-page")) }

        gateway.page(
            snapshotCall,
            PagedQuery(condition = Condition.ALL, pagination = Pagination(1, 10)),
        ).block()

        raw.lastPagedQuery!!.sort.assert().hasSize(1)
        raw.lastPagedQuery!!.sort.single().field.assert().isEqualTo("aggregateId")
    }

    @Test
    fun `typed materializer should be target-bound and run inside the gateway`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        raw.singleResult = SimpleDynamicDocument(
            linkedMapOf(
                "aggregateId" to "order-1",
                "state" to linkedMapOf("status" to "PAID"),
            ),
        )
        val materializer = QueryResultMaterializer(snapshotCall.target, TypedResult::class.java) { identity, document ->
            TypedResult(identity, document.getNestedDocument("state").getValue("status"))
        }
        val gateway = gateway(raw, resultMaterializers = listOf(materializer)) {
            Mono.just(QueryAuthority.System("test", "typed"))
        }

        gateway.single(snapshotCall, SingleQuery(Condition.ALL), TypedResult::class.java).block().assert()
            .isEqualTo(TypedResult("order-1", "PAID"))
    }

    @Test
    fun `missing typed materializer and returned-record budget should fail before storage`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val gateway = gateway(raw) { Mono.just(QueryAuthority.System("test", "fail-closed")) }

        assertThrownBy<QueryExecutionException> {
            gateway.single(snapshotCall, SingleQuery(Condition.ALL), TypedResult::class.java).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.MAPPING_FAILURE)
                error.path.assert().isEqualTo("$.result")
                error.code.assert().isEqualTo("RESULT_MAPPING_FAILED")
            },
        )
        raw.singleCalls.get().assert().isZero()

        assertThrownBy<QueryExecutionException> {
            gateway.stream(
                snapshotCall.copy(budget = QueryExecutionBudget(maxReturnedRecords = 1)),
                ListQuery(condition = Condition.ALL, limit = 2),
            ).collectList().block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.BUDGET_EXCEEDED)
                error.code.assert().isEqualTo("RESULT_LIMIT_EXCEEDED")
            },
        )
        raw.streamCalls.get().assert().isZero()
    }

    @Test
    fun `raw result should be observed once through the bounded snapshot`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val source = linkedMapOf<String, Any?>(
            "aggregateId" to "order-1",
            "state" to linkedMapOf("status" to "PAID"),
        )
        raw.singleResult = GetRejectingDynamicDocument(source)
        val gateway = gateway(raw) { Mono.just(QueryAuthority.System("test", "single-observation")) }

        gateway.single(snapshotCall, SingleQuery(Condition.ALL)).block()!!.getValue<String>("aggregateId").assert()
            .isEqualTo("order-1")
    }

    @Test
    fun `oversized raw result should fail mapping without escaping the admission budget`() {
        val raw = ProbeSnapshotQueryService(namedAggregate)
        val source = LinkedHashMap<String, Any?>()
        source["aggregateId"] = "order-1"
        repeat(1024) { index -> source["field-$index"] = index }
        raw.singleResult = SimpleDynamicDocument(source)
        val gateway = gateway(raw) { Mono.just(QueryAuthority.System("test", "bounded-result")) }

        assertThrownBy<QueryExecutionException> {
            gateway.single(snapshotCall, SingleQuery(Condition.ALL)).block()
        }.satisfies(
            Consumer { error ->
                error.category.assert().isEqualTo(QueryErrorCategory.MAPPING_FAILURE)
                error.path.assert().isEqualTo("$.result")
                error.code.assert().isEqualTo("RESULT_MAPPING_FAILED")
            },
        )
    }

    private fun gateway(
        raw: ProbeSnapshotQueryService,
        eventRaw: EventStreamQueryService = NoOpEventStreamQueryServiceFactory.create(namedAggregate),
        configuration: QueryGatewayConfiguration = QueryGatewayConfiguration(),
        resultMaterializers: Iterable<QueryResultMaterializer<*>> = emptyList(),
        authority: QueryAuthorityResolver,
    ): QueryGateway = QueryGatewayRuntime.create(
        namedAggregates = listOf(namedAggregate),
        rawServiceSource = object : QueryRawServiceSource {
            override fun snapshot(namedAggregate: NamedAggregate): SnapshotQueryService<*> = raw

            override fun eventStream(namedAggregate: NamedAggregate) = eventRaw
        },
        dialectResolver = QueryLegacyDialectResolver {
            QueryLegacyDialect(QueryElementPathMode.CURRENT_ELEMENT_RELATIVE, QueryMatchScopeMode.DOCUMENT)
        },
        authorityResolver = authority,
        resultMaterializers = resultMaterializers,
        configuration = configuration,
        clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC),
    ).gateway

    private val namedAggregate = MaterializedNamedAggregate("sales", "order")
    private val snapshotCall = QueryCall(
        QueryTarget(namedAggregate, QueryDocumentKind.SNAPSHOT),
        QueryPurpose("query-test"),
    )
    private val eventCall = QueryCall(
        QueryTarget(namedAggregate, QueryDocumentKind.EVENT_STREAM),
        QueryPurpose("query-test"),
    )

    private data class TypedResult(val identity: String, val status: String)

    private class GetRejectingDynamicDocument(
        private val delegate: MutableMap<String, Any?>,
    ) : DynamicDocument,
        MutableMap<String, Any?> by delegate {
        override fun get(key: String): Any? = error("Dynamic result must be traversed once instead of read by key.")

        override fun getNestedDocument(key: String): DynamicDocument = error("Not used by the raw snapshot boundary.")
    }

    private fun Condition.operators(): List<Operator> =
        listOf(operator) + children.flatMap { child -> child.operators() }

    private fun Condition.flatten(): List<Condition> =
        listOf(this) + children.flatMap { child -> child.flatten() }

    private class ProbeSnapshotQueryService(
        override val namedAggregate: NamedAggregate,
    ) : SnapshotQueryService<Any> {
        override val name: String = "probe"
        val singleCalls = AtomicInteger()
        val streamCalls = AtomicInteger()
        val countCalls = AtomicInteger()
        var singleResult: DynamicDocument? = null
        var countResult: Long = 0
        var lastSingleQuery: ISingleQuery? = null
        var lastCountCondition: Condition? = null
        var lastPagedQuery: IPagedQuery? = null

        override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<Any>> = Mono.empty()

        override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
            singleCalls.incrementAndGet()
            lastSingleQuery = singleQuery
            return Mono.justOrEmpty(singleResult)
        }

        override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<Any>> = Flux.empty()

        override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
            streamCalls.incrementAndGet()
            return Flux.empty()
        }

        override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<Any>>> =
            Mono.just(PagedList.empty())

        override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
            lastPagedQuery = pagedQuery
            return Mono.just(PagedList.empty())
        }

        override fun count(condition: Condition): Mono<Long> {
            countCalls.incrementAndGet()
            lastCountCondition = condition
            return Mono.just(countResult)
        }
    }

    private class ProbeEventStreamQueryService(
        override val namedAggregate: NamedAggregate,
    ) : EventStreamQueryService {
        val countCalls = AtomicInteger()
        var countResult: Long = 0
        var lastCountCondition: Condition? = null

        override fun single(singleQuery: ISingleQuery): Mono<DomainEventStream> = Mono.empty()

        override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> = Mono.empty()

        override fun list(listQuery: IListQuery): Flux<DomainEventStream> = Flux.empty()

        override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> = Flux.empty()

        override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<DomainEventStream>> = Mono.just(PagedList.empty())

        override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> =
            Mono.just(PagedList.empty())

        override fun count(condition: Condition): Mono<Long> {
            countCalls.incrementAndGet()
            lastCountCondition = condition
            return Mono.just(countResult)
        }
    }
}
