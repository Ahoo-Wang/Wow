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
import me.ahoo.wow.command.kernel.AggregateModel
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.appendResolvingOutcome
import me.ahoo.wow.exception.NotFoundResourceException
import me.ahoo.wow.messaging.propagation.MessagePropagators
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.reactor.checkpoint
import reactor.core.Exceptions
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
 * @param model The aggregate type compiled once: its command entries, error functions and sourcing table.
 * @param messagePropagator Propagates the command's context into the event streams it commits.
 */
internal class SimpleCommandAggregate<C : Any, S : Any>(
    override val state: StateAggregate<S>,
    override val commandRoot: C,
    private val eventStore: EventStore,
    private val model: AggregateModel<C, S>,
    private val messagePropagator: MessagePropagators = MessagePropagators.DEFAULT,
) : CommandAggregate<C, S>,
    NamedTypedAggregate<C> by model.metadata.command {
    private companion object {
        private val log = KotlinLogging.logger {}
        private const val PROCESSOR_NAME = "SimpleCommandAggregate"

        /** What the exchange reports until a command function runs. */
        private val PROCESS_FUNCTION =
            FunctionInfoData(
                functionKind = FunctionKind.COMMAND,
                contextName = Wow.WOW,
                processorName = PROCESSOR_NAME,
                name = "process",
            )
    }

    private val metadata = model.metadata.command

    override val processorName: String
        get() = PROCESSOR_NAME

    /**
     * Whether [state] holds events the event store does not: a sourcing function threw part-way through a stream, or
     * the stream was applied and then not appended. The in-memory state then differs from the store, so this instance
     * takes no further command and `@OnError` runs on a reloaded aggregate instead (see [RetryableAggregateProcessor]).
     */
    @Volatile
    var discarded: Boolean = false
        private set

    /**
     * Whether a command in [spaceId] addresses another space than this aggregate's: only a spaced aggregate checks,
     * and only once initialized; a blank space states none.
     */
    private fun isForeignSpace(spaceId: String): Boolean {
        if (!metadata.spaced || spaceId.isBlank()) {
            return false
        }
        return initialized && spaceId != state.spaceId
    }

    /**
     * Processes a command exchange: decide, apply, append, as one atomic unit.
     *
     * - Guards: version, existence and creation, ownership, space, deletion.
     * - Decide: the command function and its after-functions produce the event stream; the state is only read.
     * - Apply: the stream is sourced into [state], so an event that cannot be loaded is never stored.
     * - Append: the stream is committed to the event store; only then does the exchange take its version.
     *
     * When the apply or the append fails, nothing is stored or published and the command fails. An instance that
     * applied events which were not stored is [discarded], never reused. Called directly (not through
     * [RetryableAggregateProcessor], which reloads the committed state for it), `@OnError` runs on this instance: on a
     * discarded one it sees the unstored events. Only tests and hand-built processors call it that way.
     *
     * @param exchange The server command exchange to process.
     * @return A Mono containing the resulting domain event stream.
     */
    override fun process(exchange: ServerCommandExchange<*>): Mono<DomainEventStream> =
        processAttempt(exchange).onErrorResume {
            handleError(exchange, it)
        }

    /**
     * One processing attempt without the `@OnError` function: a caller that retries ([RetryableAggregateProcessor])
     * runs [handleError] once, after its final failure.
     */
    internal fun processAttempt(exchange: ServerCommandExchange<*>): Mono<DomainEventStream> {
        exchange.setFunction(PROCESS_FUNCTION)
        exchange.setAggregateVersion(version)
        val message = exchange.message
        val commandType = message.body.javaClass
        return Mono.defer {
            exchange.setCommandAggregate(this)
            log.debug {
                "Process $message."
            }
            check(!discarded) {
                "Failed to process command[${message.id}]: The current StateAggregate[${aggregateId.id}] was discarded: " +
                    "it holds events the event store does not."
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
            if (isForeignSpace(message.spaceId)) {
                return@defer IllegalAccessSpaceAggregateException(aggregateId).toMono()
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
            val commandEntry = model.commandEntry(commandType)
            requireNotNull(commandEntry) {
                "Failed to process command[${message.id}]: Undefined command[${message.body.javaClass}]."
            }
            commandEntry.invoke(this, exchange, messagePropagator).flatMap { eventStream ->
                applyThenAppend(exchange, eventStream)
            }
        }
    }

    /**
     * Applies [eventStream] to [state], then appends it. A failure of either marks this instance [discarded] when the
     * state already holds events the store does not; the exchange takes the stream's version only once it is stored.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun applyThenAppend(
        exchange: ServerCommandExchange<*>,
        eventStream: DomainEventStream
    ): Mono<DomainEventStream> {
        try {
            state.onSourcing(eventStream)
        } catch (error: Throwable) {
            Exceptions.throwIfJvmFatal(error)
            discarded = true
            log.error(error) {
                "Failed to apply DomainEventStream[${eventStream.id}] version[${eventStream.version}] of " +
                    "[$aggregateId]: nothing is stored and the state instance is discarded."
            }
            return error.toMono()
        }
        return eventStore.appendResolvingOutcome(eventStream)
            .checkpoint {
                "Append DomainEventStream[${eventStream.id}] CommandId:[${eventStream.commandId}] [SimpleCommandAggregate]"
            }
            .doOnError {
                // The state holds the stream, the store does not.
                discarded = true
            }
            .then(
                Mono.fromCallable {
                    exchange.setAggregateVersion(eventStream.version)
                    eventStream
                }
            )
    }

    /** Whether the aggregate declares an `@OnError` function for [commandType]. */
    internal fun hasErrorFunction(commandType: Class<*>): Boolean = model.errorFunction(commandType) != null

    /**
     * Handles the failure of processing [exchange] with the `@OnError` function registered for the command type.
     *
     * The error is recorded on the exchange first, then the error function runs. The resulting error is the one the
     * error function left on the exchange (it may replace it), otherwise [error]; an error thrown by the error
     * function propagates instead. Without an error function [error] propagates.
     *
     * @param exchange The server command exchange where the error occurred.
     * @param error The processing failure.
     * @return A Mono that always errors.
     */
    internal fun handleError(exchange: ServerCommandExchange<*>, error: Throwable): Mono<DomainEventStream> {
        exchange.setError(error)
        val errorFunction = model.errorFunction(exchange.message.body.javaClass) ?: return error.toMono()
        return errorFunction.invoke(commandRoot, exchange).then(
            Mono.defer {
                exchange.getError()?.toMono() ?: error.toMono<DomainEventStream>()
            }
        )
    }

    override fun toString(): String = "SimpleCommandAggregate(state=$state, metadata=$metadata, discarded=$discarded)"
}
