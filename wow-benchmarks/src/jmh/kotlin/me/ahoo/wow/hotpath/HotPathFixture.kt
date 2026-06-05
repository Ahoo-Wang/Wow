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

package me.ahoo.wow.hotpath

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import me.ahoo.cosid.cosid.ClockSyncCosIdGenerator
import me.ahoo.cosid.cosid.Radix62CosIdGenerator
import me.ahoo.cosid.provider.DefaultIdGeneratorProvider
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.command.cartAggregateMetadata
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.toDomainEventStream
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.example.api.cart.CartItem
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.id.CosIdGlobalIdGeneratorFactory
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.infra.idempotency.BloomFilterIdempotencyChecker
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.test.aggregate.GivenInitializationCommand
import java.time.Duration

object HotPathFixture {
    val namedAggregate = MaterializedNamedAggregate("example-service", "cart")

    val aggregateMetadata by lazy { cartAggregateMetadata }
    val aggregateId by lazy { aggregateMetadata.aggregateId() }

    init {
        DefaultIdGeneratorProvider.INSTANCE.set(
            CosIdGlobalIdGeneratorFactory.ID_NAME,
            ClockSyncCosIdGenerator(Radix62CosIdGenerator(0)),
        )
    }

    fun createHeader(): Header {
        return DefaultHeader()
    }

    fun createCommandMessage(): CommandMessage<AddCartItem> {
        return AddCartItem(productId = "productId").toCommandMessage(
            id = generateGlobalId(),
            requestId = generateGlobalId(),
            aggregateId = generateGlobalId(),
            namedAggregate = namedAggregate,
        )
    }

    fun createEventStream(): DomainEventStream {
        val event = CartItemAdded(CartItem("productId"))
        return listOf<Any>(event).toDomainEventStream(
            upstream = GivenInitializationCommand(aggregateId),
        )
    }

    fun createBloomFilterIdempotencyChecker(): BloomFilterIdempotencyChecker {
        return BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
            BloomFilter.create(
                Funnels.stringFunnel(Charsets.UTF_8),
                10_000_000,
                0.00001,
            )
        }
    }
}
