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
package me.ahoo.wow.modeling.command

import me.ahoo.wow.api.modeling.NamedTypedAggregate
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.modeling.matedata.CommandAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class SimpleCommandAggregate<C : Any, S : Any>(
    override val state: StateAggregate<S>,
    override val commandRoot: C,
    private val eventStore: EventStore,
    private val metadata: CommandAggregateMetadata<C>
) : CommandAggregate<C, S>,
    NamedTypedAggregate<C> by metadata {
    private companion object {
        private val log: Logger = LoggerFactory.getLogger(SimpleCommandAggregate::class.java)
    }

    override val processorName: String = metadata.processorName
    private val commandFunctionRegistry = metadata.toCommandFunctionRegistry(this)
    private val errorFunctionRegistry = metadata.toErrorFunctionRegistry(this)

    @Volatile
    override var commandState = CommandState.STORED

    override fun process(exchange: ServerCommandExchange<*>): Mono<DomainEventStream> {
        return Mono.defer {
            exchange.setCommandAggregate(this)
            val message = exchange.message
            if (log.isDebugEnabled) {
                log.debug("Process {}.", message)
            }
            if (message.aggregateVersion != null &&
                message.aggregateVersion != version
            ) {
                return@defer CommandExpectVersionConflictException(
                    command = message,
                    expectVersion = message.aggregateVersion!!,
                    actualVersion = version,
                ).toMono()
            }
            if (!initialized && !message.isCreate && !message.allowCreate) {
                return@defer NotFoundResourceException("$aggregateId is not initialized.").toMono()
            }
            check(commandState == CommandState.STORED) {
                "Failed to process command[${message.id}]: The current StateAggregate[$aggregateId] is not stored."
            }

            if (state.deleted) {
                return@defer IllegalAccessDeletedAggregateException(
                    state.aggregateId,
                ).toMono()
            }
            val commandType = message.body.javaClass
            val commandFunction = commandFunctionRegistry[commandType]
            requireNotNull(commandFunction) {
                "Failed to process command[${message.id}]: Undefined command[${message.body.javaClass}]."
            }
            commandFunction
                .invoke(exchange)
                .doOnNext {
                    exchange.setEventStream(it)
                    /**
                     * 将领域事件朔源到当前状态聚合根.
                     */
                    commandState = commandState.onSourcing(state, it)
                }
                .flatMap { eventStream ->
                    /**
                     * 持久化事件存储,完成持久化领域事件意味着命令已经完成.
                     */
                    commandState.onStore(eventStore, eventStream)
                        .doOnNext { commandState = it }
                        .doOnError { commandState = CommandState.EXPIRED }
                        .thenReturn(eventStream)
                }.onErrorResume {
                    exchange.setError(it)
                    val errorFunction = errorFunctionRegistry[commandType]
                    val errorMono = Mono.error<DomainEventStream>(it)
                    errorFunction?.invoke(exchange)?.then(errorMono) ?: errorMono
                }
        }
    }

    override fun toString(): String {
        return "SimpleCommandAggregate(state=$state, metadata=$metadata, commandState=$commandState)"
    }
}
