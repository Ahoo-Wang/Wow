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

package me.ahoo.wow.command

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import java.time.Duration

val cartAggregateMetadata by lazy {
    aggregateMetadata<Cart, CartState>()
}

private val benchmarkCart = MaterializedNamedAggregate("example-service", "cart")
const val FIXED_AGGREGATE_ID = "benchmark-cart-fixed-id"

fun createCommandMessage(): CommandMessage<AddCartItem> {
    val id = nextBenchmarkId()
    return createCommandMessage(
        id = id,
        requestId = id,
        aggregateId = FIXED_AGGREGATE_ID,
        namedAggregate = benchmarkCart,
    )
}

fun createCommandMessageForNewAggregate(): CommandMessage<AddCartItem> {
    val id = nextBenchmarkId()
    return createCommandMessage(
        id = id,
        requestId = id,
        aggregateId = nextBenchmarkId(),
        namedAggregate = benchmarkCart,
    )
}

fun createSmokeCommandMessage(): CommandMessage<AddCartItem> {
    return createCommandMessage(
        id = "benchmark-command-id",
        requestId = "benchmark-request-id",
        aggregateId = "benchmark-cart-id",
        namedAggregate = benchmarkCart,
    )
}

private fun createCommandMessage(
    id: String,
    requestId: String?,
    aggregateId: String?,
    namedAggregate: MaterializedNamedAggregate?,
): CommandMessage<AddCartItem> {
    return AddCartItem(
        productId = "productId"
    ).toCommandMessage(
        id = id,
        requestId = requestId,
        aggregateId = aggregateId,
        namedAggregate = namedAggregate,
    )
}

private fun nextBenchmarkId(): String = generateGlobalId()

fun createBloomFilterIdempotencyChecker(): BloomFilterIdempotencyChecker {
    return BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
        BloomFilter.create(
            Funnels.stringFunnel(Charsets.UTF_8),
            10_000_000,
            0.00001,
        )
    }
}
