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

import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.api.annotation.ProjectionProcessor
import me.ahoo.wow.example.api.order.AddressChanged
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.api.order.OrderPaid
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import me.ahoo.wow.serialization.toJsonString
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

@ProjectionProcessor
class OrderProjector {
    companion object {
        private val log = LoggerFactory.getLogger(OrderProjector::class.java)
    }

    @Blocking
    fun onEvent(orderCreated: OrderCreated) {
        if (log.isInfoEnabled) {
            log.info(orderCreated.toString())
        }
        // write
//        Mono.just(orderCreated).delayElement(Duration.ofSeconds(3)).doOnNext {
//            if (log.isInfoEnabled) {
//                log.info("Block Mono.just(orderCreated)")
//            }
//        }.block()
    }

    @Suppress("UnusedParameter")
    fun onStateEvent(orderCreated: OrderCreated, state: OrderState) {
        if (log.isInfoEnabled) {
            log.info(state.toJsonString())
        }
    }

    @Suppress("UnusedParameter")
    fun onStateEvent(orderCreated: OrderCreated, state: ReadOnlyStateAggregate<OrderState>) {
        if (log.isInfoEnabled) {
            log.info(state.toJsonString())
        }
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
