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

package me.ahoo.wow.event

import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.state.ReadOnlyStateAggregate
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

interface DomainEventExchange<T : Any> : MessageExchange<DomainEventExchange<T>, DomainEvent<T>> {

    fun getEventFunction(): MessageFunction<Any, DomainEventExchange<*>, Mono<*>>? {
        return getFunctionAs()
    }
}

class SimpleDomainEventExchange<T : Any>(
    override val message: DomainEvent<T>,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
) : DomainEventExchange<T>

interface StateDomainEventExchange<S : Any, T : Any> : DomainEventExchange<T> {
    val state: ReadOnlyStateAggregate<S>
    override fun <T : Any> extractDeclared(type: Class<T>): T? {
        val extracted = super.extractDeclared(type)
        if (extracted != null) {
            return extracted
        }
        if (type.isInstance(state)) {
            return type.cast(state)
        }
        if (type.isInstance(state.state)) {
            return type.cast(state.state)
        }

        return null
    }
}

class SimpleStateDomainEventExchange<S : Any, T : Any>(
    override val state: ReadOnlyStateAggregate<S>,
    override val message: DomainEvent<T>,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
) : StateDomainEventExchange<S, T>
