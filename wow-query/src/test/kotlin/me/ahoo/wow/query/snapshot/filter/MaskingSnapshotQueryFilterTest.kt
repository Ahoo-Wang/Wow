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

package me.ahoo.wow.query.snapshot.filter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.mask.StateObjectNodeMasker
import me.ahoo.wow.query.mask.StateObjectNodeMaskerRegistry
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MaskingSnapshotQueryFilterTest {
    @Test
    fun `mask should run before typed materialization exactly once for document operations`() {
        val calls = AtomicInteger()
        val gateway = gateway(
            masker { node ->
                calls.incrementAndGet()
                node.withObject("state").put("secret", "***")
                node
            },
        )

        gateway.single(singleQuery { }).block()!!.state.secret.assert().isEqualTo("***")
        gateway.dynamicSingle(
            singleQuery { }
        ).block()!!.path("state").path("secret").textValue().assert().isEqualTo("***")
        gateway.list(listQuery { }).single().block()!!.state.secret.assert().isEqualTo("***")
        gateway.dynamicList(
            listQuery { }
        ).single().block()!!.path("state").path("secret").textValue().assert().isEqualTo("***")
        gateway.paged(pagedQuery { }).block()!!.list.single().state.secret.assert().isEqualTo("***")
        gateway.dynamicPaged(pagedQuery { }).block()!!.list.single().path("state").path("secret").textValue()
            .assert().isEqualTo("***")
        calls.get().assert().isEqualTo(6)
    }

    @Test
    fun `count and aggregation should never mask`() {
        val calls = AtomicInteger()
        val gateway = gateway(
            masker { node ->
                calls.incrementAndGet()
                node
            },
        )

        gateway.count(MatchAllFilter).block().assert().isOne()
        gateway.aggregate(AggregationQuery(metrics = listOf(AggregationMetric.Count("count"))))
            .single().block()!!.path("count").longValue().assert().isOne()
        calls.get().assert().isZero()
    }

    @Test
    fun `invalid envelope mask should expose dynamic output and fail closed for typed query`() {
        val handled = CopyOnWriteArrayList<Throwable>()
        val gateway = gateway(
            masker { node ->
                node.remove("state")
                node
            },
            ErrorHandler { _, error ->
                handled += error
                Mono.empty()
            },
        )

        gateway.dynamicSingle(singleQuery { }).block()!!.has("state").assert().isFalse()
        StepVerifier.create(gateway.single(singleQuery { })).expectError().verify()
        handled.assert().hasSize(1)
    }

    @Test
    fun `mask failure should cross the gateway error boundary unchanged`() {
        val original = IllegalStateException("mask")
        val handled = CopyOnWriteArrayList<Throwable>()
        val gateway = gateway(
            masker { throw original },
            ErrorHandler { _, error ->
                handled += error
                Mono.empty()
            },
        )

        StepVerifier.create(gateway.dynamicSingle(singleQuery { }))
            .expectErrorMatches { it === original }
            .verify()
        handled.single().assert().isSameAs(original)
    }

    private fun gateway(
        masker: StateObjectNodeMasker,
        errorHandler: ErrorHandler<QueryContext<*, *>> = ErrorHandler { _, error -> Mono.error(error) },
    ): DefaultSnapshotQueryGateway<State> {
        val registry = StateObjectNodeMaskerRegistry().apply { register(masker) }
        return DefaultSnapshotQueryGateway(
            MOCK_AGGREGATE_METADATA,
            Backend,
            JsonSerializer.typeFactory.constructParametricType(MaterializedSnapshot::class.java, State::class.java),
            listOf(MaskingSnapshotQueryFilter(registry)),
            errorHandler,
        )
    }

    private fun masker(mask: (ObjectNode) -> ObjectNode) = object : StateObjectNodeMasker {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override fun mask(node: ObjectNode): ObjectNode = mask(node)
    }

    private object Backend : SnapshotQueryBackend {
        override val namedAggregate: NamedAggregate = MOCK_AGGREGATE_METADATA
        override val name: String = "mask"
        override fun single(query: ISingleQuery): Mono<ObjectNode> = Mono.fromSupplier(::snapshotNode)
        override fun list(query: IListQuery): Flux<ObjectNode> = Flux.defer { Flux.just(snapshotNode()) }
        override fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>> =
            Mono.fromSupplier { PagedList(1, listOf(snapshotNode())) }
        override fun count(filter: FilterExpression): Mono<Long> = Mono.just(1)
        override fun aggregate(query: AggregationQuery): Flux<ObjectNode> = Flux.just("""{"count":1}""".toJsonNode())
    }

    private data class State(val secret: String)

    private companion object {
        fun snapshotNode(): ObjectNode = """
            {"contextName":"mock","aggregateName":"mock","tenantId":"tenant","ownerId":"_default_",
             "spaceId":"_default_","aggregateId":"aggregate","version":1,"eventId":"event",
             "firstOperator":"operator","operator":"operator","firstEventTime":1,"eventTime":1,
             "state":{"secret":"raw"},"snapshotTime":1,"tags":{},"deleted":false}
        """.toJsonNode()
    }
}
