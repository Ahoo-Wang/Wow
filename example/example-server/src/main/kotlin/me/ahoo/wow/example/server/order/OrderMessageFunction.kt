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
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.example.api.ExampleService
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.materialize
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

@EventProcessor
class OrderMessageFunction : MessageFunction<Any, DomainEventExchange<Any>, Mono<Void>> {
    companion object {
        private val log = LoggerFactory.getLogger(OrderMessageFunction::class.java)
    }

    override val supportedTopics: Set<NamedAggregate> = setOf(aggregateMetadata<Order, OrderState>().materialize())
    override val supportedType: Class<*>
        get() = Any::class.java

    override fun <M> supportMessage(message: M): Boolean
        where M : Message<*, Any>, M : NamedBoundedContext, M : NamedAggregate {
        return supportedTopics.any {
            it.isSameAggregateName(message)
        }
    }

    override val processor: Any
        get() = this

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? {
        return null
    }

    override fun invoke(exchange: DomainEventExchange<Any>): Mono<Void> {
        if (log.isDebugEnabled) {
            log.debug(exchange.message.body.toString())
        }
        // write
        return Mono.empty()
    }

    override val functionKind: FunctionKind
        get() = FunctionKind.EVENT
    override val contextName: String
        get() = ExampleService.SERVICE_NAME
    override val name: String
        get() = OrderMessageFunction::class.java.simpleName
}
