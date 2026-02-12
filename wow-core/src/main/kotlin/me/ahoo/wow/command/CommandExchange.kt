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
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.exception.ErrorInfo.Companion.isFailed
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.event.DomainEventException.Companion.toException
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.modeling.command.AggregateProcessor
import me.ahoo.wow.modeling.command.getCommandAggregate
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import java.util.concurrent.ConcurrentHashMap

/**
 * Base interface for command exchanges that facilitate message passing during command processing.
 *
 * Command exchanges provide a context for command handling, allowing processors to access
 * and modify command-related data throughout the processing pipeline.
 *
 * @param SOURCE the type of the command exchange source
 * @param C the type of the command
 * @author ahoo wang
 * @see MessageExchange
 * @see ClientCommandExchange
 * @see ServerCommandExchange
 */
interface CommandExchange<SOURCE : CommandExchange<SOURCE, C>, C : Any> : MessageExchange<SOURCE, CommandMessage<C>>

/**
 * Client-side command exchange interface for commands sent from clients.
 *
 * This interface represents the exchange context for commands initiated by clients,
 * including the wait strategy that determines how the client should wait for results.
 *
 * @param C the type of the command
 * @property waitStrategy the strategy defining how to wait for command processing results
 * @see CommandExchange
 * @see WaitStrategy
 */
interface ClientCommandExchange<C : Any> : CommandExchange<ClientCommandExchange<C>, C> {
    val waitStrategy: WaitStrategy
}

/**
 * Simple implementation of ClientCommandExchange.
 *
 * This class provides a basic implementation of the client command exchange,
 * storing the command message, wait strategy, and any additional attributes.
 *
 * @param C the type of the command
 * @param message the command message being processed
 * @param waitStrategy the strategy for waiting on command results
 * @param attributes mutable map for storing additional exchange data
 * @see ClientCommandExchange
 */
class SimpleClientCommandExchange<C : Any>(
    override val message: CommandMessage<C>,
    override val waitStrategy: WaitStrategy,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
) : ClientCommandExchange<C>

const val COMMAND_INVOKE_RESULT_KEY = "__COMMAND_INVOKE_RESULT__"
const val EVENT_STREAM_KEY = "__EVENT_STREAM__"
const val AGGREGATE_METADATA_KEY = "__AGGREGATE_METADATA__"
const val AGGREGATE_PROCESSOR_KEY = "__AGGREGATE_PROCESSOR__"
const val AGGREGATE_VERSION_KEY = "__AGGREGATE_VERSION__"

/**
 * Server-side command exchange interface for commands being processed by the server.
 *
 * This interface provides access to command processing context, including the aggregate
 * processor, command results, event streams, and error information. It allows processors
 * to store and retrieve data throughout the command handling pipeline.
 *
 * @param C the type of the command
 * @see CommandExchange
 * @see AggregateProcessor
 * @see DomainEventStream
 */
interface ServerCommandExchange<C : Any> : CommandExchange<ServerCommandExchange<C>, C> {
    fun setAggregateMetadata(aggregateMetadata: AggregateMetadata<*, *>): ServerCommandExchange<C> =
        setAttribute(AGGREGATE_METADATA_KEY, aggregateMetadata)

    fun getAggregateMetadata(): AggregateMetadata<*, *>? = getAttribute(AGGREGATE_METADATA_KEY)

    /**
     * Sets the aggregate processor responsible for handling this command.
     *
     * @param aggregateProcessor the aggregate processor instance
     * @return this exchange instance for method chaining
     * @see AggregateProcessor
     * @see getAggregateProcessor
     */
    fun setAggregateProcessor(aggregateProcessor: AggregateProcessor<*>): ServerCommandExchange<C> =
        setAttribute(AGGREGATE_PROCESSOR_KEY, aggregateProcessor)

    /**
     * Gets the aggregate processor responsible for handling this command.
     *
     * @return the aggregate processor instance, or null if not set
     * @see AggregateProcessor
     * @see setAggregateProcessor
     */
    fun getAggregateProcessor(): AggregateProcessor<C>? = getAttribute(AGGREGATE_PROCESSOR_KEY)

    /**
     * Sets the result of invoking the command on the aggregate.
     *
     * @param result the result object returned by the command execution
     * @return this exchange instance for method chaining
     * @see getCommandInvokeResult
     */
    fun setCommandInvokeResult(result: Any): ServerCommandExchange<C> = setAttribute(COMMAND_INVOKE_RESULT_KEY, result)

    /**
     * Gets the result of invoking the command on the aggregate.
     *
     * @param R the expected type of the result
     * @return the command invocation result, or null if not set
     * @see setCommandInvokeResult
     */
    fun <R> getCommandInvokeResult(): R? = getAttribute(COMMAND_INVOKE_RESULT_KEY)

    /**
     * Sets the event stream generated by the command execution.
     *
     * @param eventStream the stream of domain events produced by the command
     * @return this exchange instance for method chaining
     * @see DomainEventStream
     * @see getEventStream
     */
    fun setEventStream(
        eventStream: DomainEventStream
    ): ServerCommandExchange<C> = setAttribute(EVENT_STREAM_KEY, eventStream)

    /**
     * Gets the event stream generated by the command execution.
     *
     * @return the domain event stream, or null if not set
     * @see DomainEventStream
     * @see setEventStream
     */
    fun getEventStream(): DomainEventStream? = getAttribute(EVENT_STREAM_KEY)

    /**
     * Sets the version of the aggregate after command processing.
     *
     * @param aggregateVersion the new aggregate version number
     * @return this exchange instance for method chaining
     * @see getAggregateVersion
     */
    fun setAggregateVersion(
        aggregateVersion: Int
    ): ServerCommandExchange<C> = setAttribute(AGGREGATE_VERSION_KEY, aggregateVersion)

    override fun getAggregateVersion(): Int? = getAttribute(AGGREGATE_VERSION_KEY)

    /**
     * Gets the error that occurred during command processing.
     *
     * This method first checks for errors set directly on the exchange,
     * then checks for failed events in the event stream.
     *
     * @return the Throwable representing the error, or null if no error occurred
     * @see DomainEvent
     * @see ErrorInfo.isFailed
     */
    override fun getError(): Throwable? {
        super.getError()?.let { return it }
        val errorEvent =
            getEventStream()?.firstOrNull {
                it.body.isFailed()
            } ?: return null
        @Suppress("UNCHECKED_CAST")
        return (errorEvent as DomainEvent<ErrorInfo>).toException()
    }

    /**
     * Extracts an object of the specified type from the exchange context.
     *
     * This method extends the parent implementation to also check for command aggregates,
     * event streams, and errors that may be stored in the exchange.
     *
     * @param T the type of object to extract
     * @param type the Class representing the type to extract
     * @return an object of type T if found, null otherwise
     * @see MessageExchange.extractDeclared
     */
    override fun <T : Any> extractDeclared(type: Class<T>): T? {
        val extracted = super.extractDeclared(type)
        if (extracted != null) {
            return extracted
        }
        val commandAggregate = getCommandAggregate<Any, Any>()
        if (commandAggregate != null) {
            if (type.isInstance(commandAggregate)) {
                return type.cast(commandAggregate)
            }
            if (type.isInstance(commandAggregate.state)) {
                return type.cast(commandAggregate.state)
            }
        }

        val eventStream = getEventStream()
        if (type.isInstance(eventStream)) {
            return type.cast(eventStream)
        }
        val error = getError()
        if (type.isInstance(error)) {
            return type.cast(error)
        }

        return null
    }
}

/**
 * Simple implementation of ServerCommandExchange.
 *
 * This class provides a basic implementation of the server command exchange,
 * storing the command message and any additional attributes for command processing.
 *
 * @param C the type of the command
 * @param message the command message being processed
 * @param attributes mutable map for storing additional exchange data
 * @see ServerCommandExchange
 */
class SimpleServerCommandExchange<C : Any>(
    override val message: CommandMessage<C>,
    override val attributes: MutableMap<String, Any> = ConcurrentHashMap()
) : ServerCommandExchange<C>
