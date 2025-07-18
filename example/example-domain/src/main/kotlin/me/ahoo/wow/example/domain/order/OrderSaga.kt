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

package me.ahoo.wow.example.domain.order

import me.ahoo.wow.api.annotation.StatelessSaga
import me.ahoo.wow.example.api.order.OrderCreated
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

@StatelessSaga
class OrderSaga {
    companion object {
        private val log = LoggerFactory.getLogger(OrderSaga::class.java)
    }

    fun onEvent(orderCreated: OrderCreated): Mono<Void> {
        if (log.isDebugEnabled) {
            log.debug(orderCreated.toString())
        }
        // write
        return Mono.empty()
    }

    @Suppress("UNUSED_PARAMETER")
    fun onStateEvent(orderCreated: OrderCreated, state: OrderState): Mono<Void> {
        if (log.isDebugEnabled) {
            log.debug(orderCreated.toString())
        }
        // write
        return Mono.empty()
    }
}
