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

package me.ahoo.wow.benchmark.runtime

import me.ahoo.test.asserts.assert
import me.ahoo.wow.benchmark.fixture.BenchmarkAggregates
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.api.cart.CartItemRemoved
import me.ahoo.wow.example.api.cart.CartQuantityChanged
import me.ahoo.wow.example.domain.cart.CartState
import org.junit.jupiter.api.Test

class SourcingFunctionRegistryVariantsTest {

    @Test
    fun `eager registry binds every sourcing handler to the current state`() {
        val state = CartState("cart-1")
        val registry = EagerSourcingFunctionRegistry(
            metadata = BenchmarkAggregates.cartMetadata.state,
            state = state,
        )

        registry.cachedFunctionCount.assert()
            .isEqualTo(BenchmarkAggregates.cartMetadata.state.sourcingFunctionRegistry.size)
        registry[CartItemAdded::class.java]!!.processor.assert().isSameAs(state)
    }

    @Test
    fun `adaptive registry binds only successfully resolved event types`() {
        val state = CartState("cart-1")
        val registry = AdaptiveSourcingFunctionRegistry(
            metadata = BenchmarkAggregates.cartMetadata.state,
            state = state,
        )

        registry.cachedFunctionCount.assert().isEqualTo(0)
        registry[String::class.java].assert().isNull()
        registry.cachedFunctionCount.assert().isEqualTo(0)

        registry[CartItemAdded::class.java]!!.processor.assert().isSameAs(state)
        registry.cachedFunctionCount.assert().isEqualTo(1)
        registry.promoted.assert().isFalse()

        registry[CartQuantityChanged::class.java]!!.processor.assert().isSameAs(state)
        registry.cachedFunctionCount.assert().isEqualTo(2)
        registry.promoted.assert().isTrue()

        registry[CartItemRemoved::class.java]!!.processor.assert().isSameAs(state)
        registry[CartItemRemoved::class.java]!!.processor.assert().isSameAs(state)
        registry.cachedFunctionCount.assert().isEqualTo(3)
    }

    @Test
    fun `adaptive map first registry preserves miss and exact key semantics`() {
        val state = CartState("cart-1")
        val registry = AdaptiveMapFirstSourcingFunctionRegistry(
            metadata = BenchmarkAggregates.cartMetadata.state,
            state = state,
        )

        registry[String::class.java].assert().isNull()
        registry.cachedFunctionCount.assert().isEqualTo(0)
        registry[CartItemAdded::class.java]!!.processor.assert().isSameAs(state)
        registry[CartItemAdded::class.java]!!.processor.assert().isSameAs(state)
        registry.cachedFunctionCount.assert().isEqualTo(1)
    }

    @Test
    fun `registries never share functions bound to different state instances`() {
        val firstState = CartState("cart-1")
        val secondState = CartState("cart-2")
        val firstRegistry = AdaptiveSourcingFunctionRegistry(
            metadata = BenchmarkAggregates.cartMetadata.state,
            state = firstState,
        )
        val secondRegistry = AdaptiveSourcingFunctionRegistry(
            metadata = BenchmarkAggregates.cartMetadata.state,
            state = secondState,
        )

        val firstFunction = firstRegistry[CartItemAdded::class.java]
        val secondFunction = secondRegistry[CartItemAdded::class.java]

        firstFunction!!.processor.assert().isSameAs(firstState)
        secondFunction!!.processor.assert().isSameAs(secondState)
        secondFunction.assert().isNotSameAs(firstFunction)
    }
}
