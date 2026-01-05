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

package me.ahoo.wow.opentelemetry.messaging

import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.messaging.MessageBus
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.opentelemetry.TraceMono
import me.ahoo.wow.opentelemetry.Traced
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface TracingMessageBus<M : Message<*, *>, E : MessageExchange<*, M>, B : MessageBus<M, E>> :
    Traced,
    MessageBus<M, E>,
    Decorator<B> {
    val producerInstrumenter: Instrumenter<M, Unit>
    override fun send(message: M): Mono<Void> {
        val source = delegate.send(message)
        val parentContext = Context.current()
        return TraceMono(
            parentContext = parentContext,
            instrumenter = producerInstrumenter,
            request = message,
            source = source,
        )
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<E> {
        return delegate.receive(namedAggregates)
    }

    override fun close() {
        delegate.close()
    }
}
