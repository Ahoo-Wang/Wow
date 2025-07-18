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

package me.ahoo.wow.example.server.order

import me.ahoo.wow.api.annotation.EventProcessor
import me.ahoo.wow.example.api.order.AddressChanged
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderPaid
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

@EventProcessor
class OrderEventProcessor {
    companion object {
        private val log = LoggerFactory.getLogger(OrderEventProcessor::class.java)
    }

    fun onEvent(orderCreated: OrderCreated): Mono<Void> {
        if (log.isDebugEnabled) {
            log.debug(orderCreated.toString())
        }
        // write
        return Mono.empty()
    }

    fun onEvent(addressChanged: AddressChanged): Mono<Void> {
        if (log.isDebugEnabled) {
            log.debug(addressChanged.toString())
        }
        // write
        return Mono.empty()
    }

    fun onEvent(orderPaid: OrderPaid): Mono<Void> {
        if (log.isDebugEnabled) {
            log.debug(orderPaid.toString())
        }
        return Mono.empty()
    }
}
