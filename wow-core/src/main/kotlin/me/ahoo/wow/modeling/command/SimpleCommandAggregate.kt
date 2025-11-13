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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.command.RecoverAggregate
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.NamedTypedAggregate
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.modeling.matedata.CommandAggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregate
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * Simple implementation of CommandAggregate that handles command processing with state management.
 *
 * This class coordinates between command execution, state aggregation, and event storage.
 * It manages the command processing lifecycle including validation, execution, event sourcing, and persistence.
 *
 * @param C The type of the command aggregate root.
 * @param S The type of the state aggregate.
 * @property state The associated state aggregate containing the current state.
 * @property commandRoot The command aggregate root instance.
 * @param eventStore The event store for persisting domain events.
 * @param metadata The metadata describing this command aggregate's configuration.
 */
class SimpleCommandAggregate<C : Any, S : Any>(
    override val state: StateAggregate<S>,
    override val commandRoot: C,
    private val eventStore: EventStore,
    private val metadata: CommandAggregateMetadata<C>
) : CommandAggregate<C, S>,
    NamedTypedAggregate<C> by metadata {
    private companion object {
        private val log = KotlinLogging.logger {}
    }

    override val processorName: String = SimpleCommandAggregate::class.simpleName!!
    private val processorFunction =
        FunctionInfoData(
            functionKind = FunctionKind.COMMAND,
            contextName = Wow.WOW,
            processorName = processorName,
            name = SimpleCommandAggregate<*, *>::process.name,
        )
    private val commandFunctionRegistry = metadata.toCommandFunctionRegistry(this)
    private val errorFunctionRegistry = metadata.toErrorFunctionRegistry(this)

    @Volatile
    override var commandState = CommandState.STORED

    /**
     * Processes a command exchange by validating, executing, and persisting the results.
     *
     * This method performs comprehensive command processing including:
     * - Version conflict checking
     * - Aggregate initialization validation
     * - Ownership validation
     * - Command execution with after-functions
     * - Event sourcing to state
     * - Event persistence
     *
     * @param exchange The server command exchange to process.
     * @return A Mono containing the resulting domain event stream.
     */
    override fun process(exchange: ServerCommandExchange<*>): Mono<DomainEventStream> {
        exchange.setFunction(processorFunction)
        exchange.setAggregateVersion(version)
        val message = exchange.message
        val commandType = message.body.javaClass
        return Mono.defer {
            exchange.setCommandAggregate(this)
            log.debug {
                "Process $message."
            }
            if (message.aggregateVersion != null && message.aggregateVersion != version) {
                return@defer CommandExpectVersionConflictException(
                    command = message,
                    expectVersion = message.aggregateVersion!!,
                    actualVersion = version,
                ).toMono()
            }
            if (!initialized && !message.isCreate && !message.allowCreate) {
                return@defer NotFoundResourceException("$aggregateId is not initialized.").toMono()
            }
            if (initialized && message.ownerId.isNotBlank() && message.ownerId != state.ownerId) {
                return@defer IllegalAccessOwnerAggregateException(aggregateId).toMono()
            }
            check(commandState == CommandState.STORED) {
                "Failed to process command[${message.id}]: The current StateAggregate[${aggregateId.id}] is not stored."
            }
            if (message.body is RecoverAggregate) {
                check(state.deleted) {
                    "Failed to process command[${message.id}]: The current StateAggregate[${aggregateId.id}] is not deleted."
                }
            } else if (state.deleted) {
                return@defer IllegalAccessDeletedAggregateException(
                    state.aggregateId,
                ).toMono()
            }
            val commandFunction = commandFunctionRegistry[commandType]
            requireNotNull(commandFunction) {
                "Failed to process command[${message.id}]: Undefined command[${message.body.javaClass}]."
            }
            commandFunction.invoke(exchange).doOnNext {
                /**
                 * 将领域事件朔源到当前状态聚合根.
                 */
                commandState = commandState.onSourcing(state, it)
            }.flatMap { eventStream ->
                /**
                 * 持久化事件存储,完成持久化领域事件意味着命令已经完成.
                 */
                exchange.setAggregateVersion(eventStream.version)
                commandState.onStore(eventStore, eventStream).doOnNext { commandState = it }
                    .doOnError { commandState = CommandState.EXPIRED }.thenReturn(eventStream)
            }
        }.errorResume(commandType, exchange)
    }

    /**
     * Extension function to handle errors during command processing using registered error functions.
     *
     * If an error function is registered for the command type, it will be invoked to handle the error.
     * Otherwise, the original error is propagated.
     *
     * @param commandType The type of the command that caused the error.
     * @param exchange The server command exchange where the error occurred.
     * @return A Mono that either contains the error handling result or re-throws the original error.
     */
    private fun Mono<DomainEventStream>.errorResume(
        commandType: Class<*>,
        exchange: ServerCommandExchange<*>
    ): Mono<DomainEventStream> {
        return onErrorResume {
            exchange.setError(it)
            val errorFunction =
                errorFunctionRegistry[commandType] ?: return@onErrorResume it.toMono<DomainEventStream>()
            errorFunction.invoke(exchange).then(
                Mono.defer {
                    exchange.getError()?.toMono() ?: it.toMono<DomainEventStream>()
                }
            )
        }
    }

    override fun toString(): String = "SimpleCommandAggregate(state=$state, metadata=$metadata, commandState=$commandState)"
}
