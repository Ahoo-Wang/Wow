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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.materialize
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.publisher.Sinks.Many
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

val BUSY_LOOPING_DURATION: Duration = Duration.ofSeconds(1)

/**
 * InMemoryCommandBus .
 *
 * @author ahoo wang
 */
class InMemoryCommandBus(
    /**
     * 一个命令只能有一个消费者，所以使用单播模式.
     *
     * @see Sinks.UnicastSpec
     */
    private val sinkSupplier: (NamedAggregate) -> Many<ServerCommandExchange<Any>> = {
        Sinks.many().unicast().onBackpressureBuffer()
    }
) : CommandBus {
    companion object {
        private val log = LoggerFactory.getLogger(InMemoryCommandBus::class.java)
    }

    private val sinks: MutableMap<NamedAggregate, Many<ServerCommandExchange<Any>>> = ConcurrentHashMap()

    private fun computeSink(namedAggregate: NamedAggregate): Many<ServerCommandExchange<Any>> {
        return sinks.computeIfAbsent(namedAggregate.materialize()) { sinkSupplier(it) }
    }

    override fun <C : Any> send(
        command: CommandMessage<C>,
    ): Mono<Void> {
        return Mono.fromRunnable {
            if (log.isDebugEnabled) {
                log.debug("Send {}.", command)
            }
            val sink = computeSink(command)
            @Suppress("UNCHECKED_CAST")
            sink.emitNext(
                SimpleServerCommandExchange(command) as ServerCommandExchange<Any>,
                Sinks.EmitFailureHandler.busyLooping(BUSY_LOOPING_DURATION)
            )
        }
    }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<Any>> {
        val sources = namedAggregates.map {
            computeSink(it).asFlux()
        }

        return Flux.merge(sources)
    }
}
