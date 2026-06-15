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

package me.ahoo.wow.webflux.route.state

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.metadata.StateAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import me.ahoo.wow.webflux.route.policy.TracingRequest
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.kotlin.test.test
import tools.jackson.databind.node.ObjectNode

class AggregateTracingReplayTest {

    @Test
    fun `full history replay should emit before source completes`() {
        val sink = Sinks.many().unicast().onBackpressureBuffer<DomainEventStream>()

        AggregateTracingReplay.trace(
            stateAggregateMetadata = CART_AGGREGATE_METADATA.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = sink.asFlux(),
            tracingRequest = TracingRequest(headVersion = null, tailVersion = null, limit = null),
        ).test()
            .then {
                sink.tryEmitNext(cartEventStreams(eventCount = 1).single())
            }
            .consumeNextWith {
                it.state.assertJsonState().itemProductIds().assert().isEqualTo(listOf("product-1"))
            }
            .thenCancel()
            .verify()
    }

    @Test
    fun `explicit range replay should emit requested window with sourced prefix`() {
        AggregateTracingReplay.trace(
            stateAggregateMetadata = CART_AGGREGATE_METADATA.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = Flux.fromIterable(cartEventStreams(eventCount = 4)),
            tracingRequest = TracingRequest(headVersion = 2, tailVersion = 3, limit = null),
        ).collectList()
            .test()
            .consumeNextWith { tracedStates ->
                tracedStates.assert().hasSize(2)
                tracedStates[0].state.assertJsonState()
                    .itemProductIds()
                    .assert()
                    .isEqualTo(listOf("product-1", "product-2"))
                tracedStates[1].state.assertJsonState()
                    .itemProductIds()
                    .assert()
                    .isEqualTo(listOf("product-1", "product-2", "product-3"))
            }
            .verifyComplete()
    }

    @Test
    fun `explicit range replay should not serialize sourced prefix states`() {
        AggregateTracingReplay.trace(
            stateAggregateMetadata = CART_AGGREGATE_METADATA.state,
            stateAggregateFactory = PrefixStateSerializationGuardFactory(minSerializableVersion = 2),
            eventStreams = Flux.fromIterable(cartEventStreams(eventCount = 2)),
            tracingRequest = TracingRequest(headVersion = 2, tailVersion = 2, limit = null),
        ).test()
            .consumeNextWith {
                it.state.assertJsonState().itemProductIds().assert().isEqualTo(listOf("product-1", "product-2"))
            }
            .verifyComplete()
    }

    @Test
    fun `tail limit replay should emit only bounded tail states`() {
        AggregateTracingReplay.trace(
            stateAggregateMetadata = CART_AGGREGATE_METADATA.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = Flux.fromIterable(cartEventStreams(eventCount = 4)),
            tracingRequest = TracingRequest(headVersion = null, tailVersion = null, limit = 2),
        ).collectList()
            .test()
            .consumeNextWith { tracedStates ->
                tracedStates.assert().hasSize(2)
                tracedStates[0].state.assertJsonState()
                    .itemProductIds()
                    .assert()
                    .isEqualTo(listOf("product-1", "product-2", "product-3"))
                tracedStates[1].state.assertJsonState()
                    .itemProductIds()
                    .assert()
                    .isEqualTo(listOf("product-1", "product-2", "product-3", "product-4"))
            }
            .verifyComplete()
    }

    @Test
    fun `zero tail limit replay should emit empty history`() {
        AggregateTracingReplay.trace(
            stateAggregateMetadata = CART_AGGREGATE_METADATA.state,
            stateAggregateFactory = ConstructorStateAggregateFactory,
            eventStreams = Flux.fromIterable(cartEventStreams(eventCount = 2)),
            tracingRequest = TracingRequest(headVersion = null, tailVersion = null, limit = 0),
        ).test()
            .verifyComplete()
    }

    private companion object {
        val CART_AGGREGATE_METADATA = aggregateMetadata<Cart, CartState>()

        fun cartEventStreams(eventCount: Int): List<DomainEventStream> {
            val aggregateId = CART_AGGREGATE_METADATA.aggregateId("streaming-trace-cart")
            val upstream = GivenInitializationCommand(aggregateId)
            return (1..eventCount).map { version ->
                CartItemAdded(CartItem(productId = "product-$version", quantity = version))
                    .toDomainEventStream(
                        upstream = upstream,
                        aggregateVersion = version - 1,
                    )
            }
        }

        fun Any.assertJsonState(): ObjectNode {
            this.assert().isInstanceOf(ObjectNode::class.java)
            return this as ObjectNode
        }

        fun ObjectNode.itemProductIds(): List<String> {
            val items = this["items"]
            return (0 until items.size()).map { index ->
                items[index]["productId"].asString()
            }
        }
    }

    private class PrefixStateSerializationGuardFactory(
        private val minSerializableVersion: Int,
        private val delegate: StateAggregateFactory = ConstructorStateAggregateFactory
    ) : StateAggregateFactory {
        override fun <S : Any> create(
            metadata: StateAggregateMetadata<S>,
            aggregateId: AggregateId
        ): StateAggregate<S> {
            return PrefixStateSerializationGuard(
                delegate = delegate.create(metadata, aggregateId),
                minSerializableVersion = minSerializableVersion,
            )
        }
    }

    private class PrefixStateSerializationGuard<S : Any>(
        private val delegate: StateAggregate<S>,
        private val minSerializableVersion: Int
    ) : StateAggregate<S> by delegate {
        override val state: S
            get() {
                check(delegate.version >= minSerializableVersion) {
                    "Prefix state should not be serialized."
                }
                return delegate.state
            }

        override fun onSourcing(eventStream: DomainEventStream): StateAggregate<S> {
            delegate.onSourcing(eventStream)
            return this
        }
    }
}
