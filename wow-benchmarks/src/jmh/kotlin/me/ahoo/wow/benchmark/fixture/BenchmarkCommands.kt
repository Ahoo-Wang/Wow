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

package me.ahoo.wow.benchmark.fixture

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.example.api.cart.AddCartItem
import me.ahoo.wow.modeling.MaterializedNamedAggregate

object BenchmarkCommands {
    fun fixedAggregateAddCartItem(): CommandMessage<AddCartItem> {
        val id = BenchmarkIds.nextGlobalId()
        return addCartItem(
            id = id,
            requestId = id,
            aggregateId = BenchmarkAggregates.FIXED_AGGREGATE_ID,
            namedAggregate = BenchmarkAggregates.namedAggregate,
        )
    }

    fun newAggregateAddCartItem(): CommandMessage<AddCartItem> {
        val id = BenchmarkIds.nextGlobalId()
        return addCartItem(
            id = id,
            requestId = id,
            aggregateId = BenchmarkIds.nextGlobalId(),
            namedAggregate = BenchmarkAggregates.namedAggregate,
        )
    }

    fun smokeAddCartItem(): CommandMessage<AddCartItem> {
        return addCartItem(
            id = "benchmark-command-id",
            requestId = "benchmark-request-id",
            aggregateId = "benchmark-cart-id",
            namedAggregate = BenchmarkAggregates.namedAggregate,
        )
    }

    private fun addCartItem(
        id: String,
        requestId: String?,
        aggregateId: String?,
        namedAggregate: MaterializedNamedAggregate?,
    ): CommandMessage<AddCartItem> {
        return AddCartItem(productId = "productId").toCommandMessage(
            id = id,
            requestId = requestId,
            aggregateId = aggregateId,
            namedAggregate = namedAggregate,
        )
    }
}
