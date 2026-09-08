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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.abac.AbacTags
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.filter.AbacQueryPolicy
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import reactor.test.StepVerifier
import reactor.util.context.Context
import reactor.util.context.ContextView
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class QueryGatewaySubscriptionTest {
    @Test
    fun `empty prepare is a protocol error before backend invocation`() {
        val calls = AtomicInteger()
        val filter = object : QueryFilter {
            override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> = Mono.empty()
        }
        gateway(
            backend {
                calls.incrementAndGet()
                Mono.empty()
            },
            filters = listOf(filter)
        )
            .dynamicSingle(SingleQuery(MatchAllFilter)).test()
            .expectErrorMatches { it is IllegalStateException && it.message!!.contains("prepare") }.verify()
        calls.get().assert().isZero()
    }

    @Test
    fun `prepare recovery cannot catch a backend error`() {
        val original = IllegalStateException("backend")
        val recoveryCalls = AtomicInteger()
        val filter = object : QueryFilter {
            override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> =
                Mono.just(context.query).onErrorResume {
                    recoveryCalls.incrementAndGet()
                    Mono.just(context.query)
                }
        }
        gateway(backend { Mono.error(original) }, filters = listOf(filter))
            .dynamicSingle(SingleQuery(MatchAllFilter)).test().expectErrorMatches { it === original }.verify()
        recoveryCalls.get().assert().isZero()
    }

    @Test
    fun `prepare cannot replace captured request scope or identity`() {
        val received = mutableListOf<FilterExpression>()
        val filter = object : QueryFilter {
            override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> =
                Mono.deferContextual { local ->
                    local.get<String>("principal").assert().isEqualTo("intruder")
                    Mono.just(context.query.withFilter(MatchAllFilter))
                }.contextWrite { Context.of("principal", "intruder") }
        }
        val policy = object : AbacQueryPolicy() {
            override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*>): Mono<AbacTags> =
                Mono.just(emptyMap())
            override fun resolveFilter(contextView: ContextView, context: QueryContext<*>): Mono<FilterExpression> =
                Mono.just(OwnerIdFilter(contextView.get("principal")))
        }
        val gateway = gateway(
            backend(onQuery = { received += it }) { Mono.empty() },
            filters = listOf(filter),
            policies = listOf(policy),
        )
        gateway.dynamicSingle(SingleQuery(TenantIdFilter("user-input")))
            .contextWrite { it.put("principal", "trusted").withQueryScope(TenantIdFilter("tenant")) }
            .test().verifyComplete()
        received.single().assert().isEqualTo(
            TenantIdFilter("tenant").appendFilter(OwnerIdFilter("trusted"))
                .appendFilter(DeletionFilter(DeletionState.ACTIVE)),
        )
    }

    @Test
    fun `snapshot policies share one prepared scoped context and only append access conditions`() {
        val received = mutableListOf<FilterExpression>()
        val contexts = mutableListOf<QueryContext<*>>()
        val policies = listOf(OwnerIdFilter("owner"), TenantIdFilter("authorized-tenant")).map { access ->
            object : AbacQueryPolicy() {
                override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*>): Mono<AbacTags> =
                    Mono.just(emptyMap())

                override fun resolveFilter(contextView: ContextView, context: QueryContext<*>): Mono<FilterExpression> {
                    contexts += context
                    return Mono.just(access)
                }
            }
        }
        val query = SingleQuery(TenantIdFilter("user-input"))
        gateway(backend(onQuery = { received += it }) { Mono.empty() }, policies = policies)
            .dynamicSingle(query)
            .contextWrite { it.withQueryScope(TenantIdFilter("trusted-scope")) }
            .test().verifyComplete()

        contexts.assert().hasSize(2)
        contexts[0].assert().isSameAs(contexts[1])
        contexts[0].query.assert().isEqualTo(query.appendFilter(TenantIdFilter("trusted-scope")))
        received.single().assert().isEqualTo(
            query.filter.appendFilter(TenantIdFilter("trusted-scope"))
                .appendFilter(OwnerIdFilter("owner").appendFilter(TenantIdFilter("authorized-tenant")))
                .appendFilter(DeletionFilter(DeletionState.ACTIVE)),
        )
    }

    @Test
    fun `empty snapshot policy is rejected before backend invocation`() {
        val calls = AtomicInteger()
        val policy = object : AbacQueryPolicy() {
            override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*>): Mono<AbacTags> =
                Mono.just(emptyMap())

            override fun resolveFilter(contextView: ContextView, context: QueryContext<*>): Mono<FilterExpression> =
                Mono.empty()
        }
        val backend = backend {
            calls.incrementAndGet()
            Mono.empty()
        }
        gateway(backend, policies = listOf(policy))
            .dynamicSingle(SingleQuery(MatchAllFilter)).test()
            .expectErrorMatches { it is IllegalStateException && it.message!!.contains("AbacQueryPolicy") }.verify()
        calls.get().assert().isZero()
    }

    @Test
    fun `default deletion selection respects only root and AND scopes`() {
        val received = mutableListOf<FilterExpression>()
        val gateway = gateway(backend(onQuery = { received += it }) { Mono.empty() })
        val explicit = DeletionFilter(DeletionState.ALL)
        val conjunction = TenantIdFilter("tenant").appendFilter(explicit)
        val disjunction = OrFilter(listOf(TenantIdFilter("tenant"), explicit))
        listOf(MatchAllFilter, explicit, conjunction, disjunction).forEach {
            gateway.dynamicSingle(SingleQuery(it)).block()
        }
        received.assert().isEqualTo(
            listOf(
                DeletionFilter(DeletionState.ACTIVE),
                explicit,
                conjunction,
                disjunction.appendFilter(DeletionFilter(DeletionState.ACTIVE)),
            )
        )
    }

    @Test
    fun `each subscription pins one schema through prepare and backend`() {
        val schemas = ConcurrentLinkedQueue(
            listOf(
                gatewaySchema(QueryModel.SNAPSHOT, emptySet(), emptyMap()),
                gatewaySchema(QueryModel.SNAPSHOT, emptySet(), emptyMap()),
            )
        )
        val providerCalls = AtomicInteger()
        val provider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.fromSupplier {
                providerCalls.incrementAndGet()
                checkNotNull(schemas.poll())
            }
            override fun refresh(): Mono<QueryModelSchema> = schema()
        }
        val prepared = CopyOnWriteArrayList<QueryModelSchema>()
        val executed = CopyOnWriteArrayList<QueryModelSchema>()
        val publisher = gateway(
            backend {
                executed += it
                Mono.fromSupplier(::snapshotNode)
            },
            provider,
            filters = listOf(recordPrepare { prepared += it.schema }),
        ).dynamicSingle(SingleQuery(MatchAllFilter))
        publisher.repeat(1).test().expectNextCount(2).verifyComplete()
        providerCalls.get().assert().isEqualTo(2)
        prepared.zip(executed).forEach { (left, right) -> left.assert().isSameAs(right) }
        prepared.map(System::identityHashCode).toSet().assert().hasSize(2)
    }

    @Test
    fun `schema failure is observed without constructing context or invoking backend`() {
        val original = QuerySchemaUnavailableException("unavailable")
        val prepares = AtomicInteger()
        val calls = AtomicInteger()
        val observed = mutableListOf<Throwable>()
        val provider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.error(original)
            override fun refresh(): Mono<QueryModelSchema> = schema()
        }
        gateway(
            backend {
                calls.incrementAndGet()
                Mono.empty()
            },
            provider,
            filters = listOf(recordPrepare { prepares.incrementAndGet() }),
            observer = errorObserver { observed += it },
        ).dynamicSingle(SingleQuery(MatchAllFilter)).test().expectErrorMatches { it === original }.verify()
        prepares.get().assert().isZero()
        calls.get().assert().isZero()
        observed.single().assert().isSameAs(original)
    }

    @Test
    fun `empty schema is a protocol error for mono and flux`() {
        val errors = mutableListOf<Throwable>()
        val provider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.empty()
            override fun refresh(): Mono<QueryModelSchema> = schema()
        }
        val gateway = gateway(backend { Mono.empty() }, provider, observer = errorObserver { errors += it })
        listOf<Publisher<*>>(
            gateway.dynamicSingle(SingleQuery(MatchAllFilter)),
            gateway.dynamicList(me.ahoo.wow.api.query.ListQuery(MatchAllFilter)),
        ).forEach { publisher ->
            StepVerifier.create(publisher).expectErrorMatches {
                it is IllegalStateException && it.message!!.contains("schema")
            }.verify()
        }
        errors.assert().hasSize(2)
    }

    @Test
    fun `policy list is captured when gateway is assembled`() {
        val policies = mutableListOf<AbacQueryPolicy>()
        val received = mutableListOf<FilterExpression>()
        val gateway = gateway(backend(onQuery = { received += it }) { Mono.empty() }, policies = policies)
        policies += object : AbacQueryPolicy() {
            override fun getPrincipalTags(contextView: ContextView, context: QueryContext<*>): Mono<AbacTags> =
                Mono.error(IllegalStateException("added after assembly"))
        }
        gateway.dynamicSingle(SingleQuery(MatchAllFilter)).test().verifyComplete()
        received.single().assert().isEqualTo(DeletionFilter(DeletionState.ACTIVE))
    }

    @Test
    fun `repeat retry and concurrent subscriptions isolate contexts and raw nodes`() {
        assertIsolated { it.repeat(1) }
        assertIsolated { publisher ->
            val attempts = AtomicInteger()
            publisher.flatMap {
                if (attempts.getAndIncrement() == 0) {
                    Mono.error(
                        IllegalStateException("retry")
                    )
                } else {
                    Mono.just(it)
                }
            }
                .retry(1)
        }
        assertIsolated { publisher ->
            Flux.merge(
                publisher.subscribeOn(Schedulers.parallel()),
                publisher.subscribeOn(Schedulers.parallel()),
            )
        }
    }

    @Test
    fun `cursor rejects an unknown model instead of guessing event identity`() {
        val calls = AtomicInteger()
        val schema = gatewaySchema(me.ahoo.wow.api.query.schema.QueryModel("CUSTOM"))
        val provider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)
            override fun refresh(): Mono<QueryModelSchema> = schema()
        }
        gateway(
            backend(cursor = {
                calls.incrementAndGet()
                Mono.just(CursorPage(emptyList(), null))
            }) { Mono.empty() },
            provider
        ).dynamicCursor(CursorQuery(MatchAllFilter)).test()
            .expectError(me.ahoo.wow.query.schema.QuerySchemaValidationException::class.java).verify()
        calls.get().assert().isZero()
    }

    @Test
    fun `gateway adds snapshot cursor identity once and preserves its explicit direction`() {
        val received = mutableListOf<ICursorQuery>()
        val gateway = gateway(
            backend(cursor = { query ->
                received += query
                Mono.just(CursorPage(emptyList(), null))
            }) { Mono.empty() }
        )
        val original = CursorQuery(MatchAllFilter)
        gateway.dynamicCursor(original).block()
        val explicit = me.ahoo.wow.api.query.Sort(QueryField("aggregateId"), me.ahoo.wow.api.query.Sort.Direction.DESC)
        gateway.dynamicCursor(CursorQuery(MatchAllFilter, sort = listOf(explicit))).block()
        original.sort.assert().isEmpty()
        received[0].sort.assert().containsExactly(
            me.ahoo.wow.api.query.Sort(QueryField("aggregateId"), me.ahoo.wow.api.query.Sort.Direction.ASC)
        )
        received[1].sort.assert().containsExactly(explicit)
    }

    @Test
    fun `cursor repeat isolates page nodes`() {
        val contexts = CopyOnWriteArrayList<QueryContext<*>>()
        val pages = gateway(
            backend(cursor = { Mono.fromSupplier { CursorPage(listOf(snapshotNode()), "next") } }) { Mono.empty() },
            filters = listOf(recordPrepare { contexts += it }),
        ).dynamicCursor(CursorQuery(MatchAllFilter)).repeat(1).collectList().block()!!
        contexts.map(System::identityHashCode).toSet().assert().hasSize(2)
        pages.map { System.identityHashCode(it.list.single()) }.toSet().assert().hasSize(2)
    }

    @Test
    fun `observer failure cannot replace original backend failure or mutate it`() {
        val original = IllegalStateException("backend")
        val observerFailure = AssertionError("observer")
        gateway(backend { Mono.error(original) }, observer = errorObserver { throw observerFailure })
            .dynamicSingle(SingleQuery(MatchAllFilter)).test()
            .expectErrorMatches { it === original && it.suppressed.isEmpty() }.verify()
    }

    @Test
    fun `observer failure cannot replace successful completion`() {
        val observer = object : QueryObserver {
            override fun onComplete(
                namedAggregate: NamedAggregate,
                queryType: QueryType
            ) { throw AssertionError("observer") }
        }
        gateway(backend { Mono.fromSupplier(::snapshotNode) }, observer = observer)
            .dynamicSingle(SingleQuery(MatchAllFilter)).test().expectNextCount(1).verifyComplete()
    }

    @Test
    fun `empty backend mono and flux complete normally`() {
        val completed = mutableListOf<QueryType>()
        val observer = object : QueryObserver {
            override fun onComplete(namedAggregate: NamedAggregate, queryType: QueryType) { completed += queryType }
        }
        val gateway = gateway(backend { Mono.empty() }, observer = observer)
        gateway.dynamicSingle(SingleQuery(MatchAllFilter)).test().verifyComplete()
        gateway.dynamicList(me.ahoo.wow.api.query.ListQuery(MatchAllFilter)).test().verifyComplete()
        completed.assert().isEqualTo(listOf(QueryType.SINGLE, QueryType.LIST))
    }

    @Test
    fun `order annotations control prepare order`() {
        val calls = mutableListOf<String>()
        val gateway = gateway(
            backend {
                calls += "backend"
                Mono.empty()
            },
            filters = listOf(
                LaterPrepare(calls),
                EarlierPrepare(calls),
            )
        )
        gateway.dynamicSingle(SingleQuery(MatchAllFilter)).test().verifyComplete()
        calls.assert().isEqualTo(listOf("early", "late", "backend"))
    }

    @Test
    fun `typed materialization failure is observed as error`() {
        val errors = mutableListOf<Throwable>()
        gateway(
            backend { Mono.just(snapshotNode().apply { remove("state") }) },
            observer = errorObserver { errors += it }
        )
            .single(SingleQuery(MatchAllFilter)).test().expectErrorMatches { it === errors.single() }.verify()
    }

    @Test
    fun `prepare rejection is observed and stops backend`() {
        val original = IllegalStateException("prepare")
        val errors = mutableListOf<Throwable>()
        val calls = AtomicInteger()
        val filter = object : QueryFilter {
            override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> = Mono.error(original)
        }
        gateway(
            backend {
                calls.incrementAndGet()
                Mono.empty()
            },
            filters = listOf(filter),
            observer = errorObserver { errors += it }
        )
            .dynamicSingle(SingleQuery(MatchAllFilter)).test().expectErrorMatches { it === original }.verify()
        calls.get().assert().isZero()
        errors.single().assert().isSameAs(original)
    }

    @Test
    fun `taking a mono value reports only its actual cancellation terminal`() {
        val terminals = mutableListOf<String>()
        val observer = object : QueryObserver {
            override fun onComplete(namedAggregate: NamedAggregate, queryType: QueryType) { terminals += "complete" }
            override fun onCancel(namedAggregate: NamedAggregate, queryType: QueryType) { terminals += "cancel" }
        }
        gateway(backend { Mono.empty() }, observer = observer)
            .count(MatchAllFilter).flux().take(1).test().expectNext(0L).verifyComplete()
        terminals.assert().containsExactly("cancel")
    }

    @Test
    fun `stream observation follows terminal completion error and cancellation`() {
        val terminals = mutableListOf<String>()
        val observer = object : QueryObserver {
            override fun onComplete(namedAggregate: NamedAggregate, queryType: QueryType) { terminals += "complete" }
            override fun onError(
                namedAggregate: NamedAggregate,
                queryType: QueryType,
                error: Throwable
            ) { terminals += "error" }
            override fun onCancel(namedAggregate: NamedAggregate, queryType: QueryType) { terminals += "cancel" }
        }
        val original = IllegalStateException("late-error")
        val rows = listOf(
            Flux.just(snapshotNode()),
            Flux.concat(Flux.just(snapshotNode()), Flux.error(original)),
            Flux.concat(Flux.just(snapshotNode()), Flux.never()),
        )
        rows.forEachIndexed { index, source ->
            val publisher = gateway(backend(list = { source }) { Mono.empty() }, observer = observer)
                .dynamicList(me.ahoo.wow.api.query.ListQuery(MatchAllFilter))
            val verifier = publisher.test().assertNext { terminals.size.assert().isEqualTo(index) }
            when (index) {
                0 -> verifier.verifyComplete()
                1 -> verifier.expectErrorMatches { it === original }.verify()
                else -> verifier.thenCancel().verify()
            }
        }
        terminals.assert().isEqualTo(listOf("complete", "error", "cancel"))
    }

    @Test
    fun `cancellation while schema is loading is observed`() {
        val cancelled = AtomicInteger()
        val provider = object : QueryModelSchemaProvider {
            override fun schema(): Mono<QueryModelSchema> = Mono.never()
            override fun refresh(): Mono<QueryModelSchema> = schema()
        }
        val observer = object : QueryObserver {
            override fun onCancel(namedAggregate: NamedAggregate, queryType: QueryType) { cancelled.incrementAndGet() }
        }
        gateway(backend { Mono.empty() }, provider, observer = observer)
            .dynamicSingle(SingleQuery(MatchAllFilter)).test().thenCancel().verify()
        cancelled.get().assert().isOne()
    }

    @me.ahoo.wow.api.annotation.Order(1)
    private class EarlierPrepare(private val calls: MutableList<String>) : QueryFilter {
        override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> {
            calls += "early"
            return Mono.just(context.query)
        }
    }

    @me.ahoo.wow.api.annotation.Order(2)
    private class LaterPrepare(private val calls: MutableList<String>) : QueryFilter {
        override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> {
            calls += "late"
            return Mono.just(context.query)
        }
    }

    private fun recordPrepare(record: (QueryContext<*>) -> Unit) = object : QueryFilter {
        override fun <Q : RewritableFilter<Q>> prepare(context: QueryContext<Q>): Mono<Q> {
            record(context)
            return Mono.just(context.query)
        }
    }

    private fun errorObserver(record: (Throwable) -> Unit) = object : QueryObserver {
        override fun onError(namedAggregate: NamedAggregate, queryType: QueryType, error: Throwable) = record(error)
    }

    private fun assertIsolated(resubscribe: (Mono<ObjectNode>) -> Publisher<ObjectNode>) {
        val contexts = CopyOnWriteArrayList<QueryContext<*>>()
        val nodes = CopyOnWriteArrayList<ObjectNode>()
        val publisher = gateway(
            backend { Mono.fromSupplier { snapshotNode().also { nodes += it } } },
            filters = listOf(recordPrepare { contexts += it }),
        ).dynamicSingle(SingleQuery(MatchAllFilter))
        Flux.from(resubscribe(publisher)).collectList().block()
        contexts.map(System::identityHashCode).toSet().assert().hasSize(2)
        nodes.map(System::identityHashCode).toSet().assert().hasSize(2)
    }

    private fun gateway(
        backend: SnapshotQueryBackend,
        provider: QueryModelSchemaProvider = schemaProvider,
        filters: List<QueryFilter> = emptyList(),
        policies: List<AbacQueryPolicy> = emptyList(),
        observer: QueryObserver = object : QueryObserver {},
    ) = DefaultSnapshotQueryGateway<TestState>(
        MOCK_AGGREGATE_METADATA,
        QueryBackendBinding(backend, provider),

        JsonSerializer.typeFactory.constructParametricType(MaterializedSnapshot::class.java, TestState::class.java),
        filters,
        policies,
        observer,
    )

    private fun backend(
        cursor: (ICursorQuery) -> Mono<CursorPage<ObjectNode>> = {
            Mono.just(CursorPage(emptyList(), null))
        },
        list: () -> Flux<ObjectNode> = { Flux.empty() },
        onQuery: (FilterExpression) -> Unit = {},
        single: (QueryModelSchema) -> Mono<ObjectNode>,
    ) = object : SnapshotQueryBackend {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override val name: String = "subscription"
        override fun single(query: ISingleQuery, schema: QueryModelSchema): Mono<ObjectNode> {
            onQuery(query.filter)
            return single(schema)
        }
        override fun list(query: IListQuery, schema: QueryModelSchema): Flux<ObjectNode> = list()
        override fun paged(query: IPagedQuery, schema: QueryModelSchema): Mono<PagedList<ObjectNode>> = Mono.just(
            PagedList.empty()
        )
        override fun cursor(query: ICursorQuery, schema: QueryModelSchema): Mono<CursorPage<ObjectNode>> = cursor(query)
        override fun count(query: FilterExpression, schema: QueryModelSchema): Mono<Long> = Mono.just(0)
        override fun aggregate(query: AggregationQuery, schema: QueryModelSchema): Flux<ObjectNode> = Flux.empty()
    }

    private data class TestState(val value: String)

    private companion object {
        val schemaProvider = object : QueryModelSchemaProvider {
            private val cursorField = QueryField("aggregateId")
            private val schema = gatewaySchema(
                QueryModel.SNAPSHOT,
                emptySet(),
                mapOf(cursorField to stringValueSchema()),
            )
            override fun schema(): Mono<QueryModelSchema> = Mono.just(schema)
            override fun refresh(): Mono<QueryModelSchema> = schema()
        }

        fun stringValueSchema() = me.ahoo.wow.query.schema.QueryValueSchema(
            me.ahoo.wow.api.query.schema.QueryValueKind.SCALAR,
            valueTypes = setOf(QueryValueType.STRING),
        )

        fun snapshotNode(): ObjectNode = """
            {"contextName":"mock","aggregateName":"mock","tenantId":"tenant","ownerId":"_default_",
             "spaceId":"_default_","aggregateId":"aggregate","version":1,"eventId":"event",
             "firstOperator":"operator","operator":"operator","firstEventTime":1,"eventTime":1,
             "state":{"value":"state-value"},"snapshotTime":1,"tags":{},"deleted":false}
        """.toJsonNode()
    }
}
