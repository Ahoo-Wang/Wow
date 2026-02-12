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

package me.ahoo.wow.eventsourcing.snapshot.dispatcher

import me.ahoo.wow.api.annotation.ORDER_DEFAULT
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStrategy
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.messaging.handler.ExchangeAck.finallyAck
import me.ahoo.wow.messaging.handler.ExchangeFilter
import reactor.core.publisher.Mono

@FilterType(SnapshotDispatcher::class)
@Order(ORDER_DEFAULT)
class SnapshotFunctionFilter(private val snapshotStrategy: SnapshotStrategy) : ExchangeFilter<StateEventExchange<*>> {
    override fun filter(exchange: StateEventExchange<*>, next: FilterChain<StateEventExchange<*>>): Mono<Void> {
        return snapshotStrategy.onEvent(exchange)
            .checkpoint("OnEvent Message[${exchange.message.id}] [SnapshotFunctionFilter]")
            .finallyAck(exchange)
            .then(next.filter(exchange))
    }
}
