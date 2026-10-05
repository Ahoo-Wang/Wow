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

package me.ahoo.wow.modeling.command.dispatcher

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.command.DuplicateRequestIdException
import me.ahoo.wow.command.RequestIdChecker
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.thenNotifyAndForget
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.state.StateEvent.Companion.toStateEvent
import me.ahoo.wow.eventsourcing.state.StateEventBus
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.filter.LogResumeErrorHandler
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.messaging.function.logErrorResume
import me.ahoo.wow.messaging.handler.ExchangeAck.finallyAck
import me.ahoo.wow.modeling.command.AggregateProcessorFactory
import me.ahoo.wow.modeling.command.getCommandAggregate
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.reactor.checkpoint
import reactor.core.publisher.Mono

/**
 * Handles the commands of one aggregate type that a [CommandDispatcher] receives.
 */
interface CommandHandler {
    /**
     * Handles [exchange], a command for an aggregate described by [aggregateMetadata]. The returned `Mono` completes
     * once the command has been handled, successfully or not; it does not fail for a command that failed.
     */
    fun handle(exchange: ServerCommandExchange<*>, aggregateMetadata: AggregateMetadata<*, *>): Mono<Void>
}

/**
 * The command side's pipeline, in a fixed order (V5: there is no command filter chain):
 *
 * 1. Each [CommandInstrumentation] wraps everything below; the first one is the outermost.
 * 2. When there is a [commandWaitNotifier], the `PROCESSED` wait signal is reported once everything below completed
 *    or failed; a failed signal carries the error.
 * 3. When there is a [requestIdChecker], the command's request ID is checked on this node, the one that processes it,
 *    before the aggregate runs: a request ID this aggregate already committed fails the command with
 *    [DuplicateRequestIdException] without running its handler. The event store's unique request ID still guards
 *    the append.
 * 4. The aggregate processes the command through an [AggregateProcessorFactory] processor. The transport message is
 *    acknowledged whatever the outcome; a failure skips the publication.
 * 5. The domain event stream the command committed is sent on [domainEventBus]; a failure propagates.
 * 6. When the state applied that stream (its version is the stream's), the state event is sent on [stateEventBus]; a
 *    failure is logged and resumed.
 *
 * A failure that reaches the end is recorded on the exchange and given to [errorHandler]. A `null` bus or notifier
 * skips its step; the Spring wiring passes all of them.
 */
@InternalWowApi
class DefaultCommandHandler(
    private val serviceProvider: ServiceProvider,
    private val aggregateProcessorFactory: AggregateProcessorFactory,
    private val domainEventBus: DomainEventBus?,
    private val stateEventBus: StateEventBus?,
    private val commandWaitNotifier: CommandWaitNotifier?,
    instrumentations: List<CommandInstrumentation> = emptyList(),
    private val requestIdChecker: RequestIdChecker? = null,
    private val errorHandler: ErrorHandler<ServerCommandExchange<*>> = LogResumeErrorHandler(),
) : CommandHandler {
    private companion object {
        private val log = KotlinLogging.logger {}
    }

    /** Innermost first, so wrapping them in turn makes the first one the outermost. */
    private val instrumentationsInnermostFirst: List<CommandInstrumentation> =
        instrumentations.sortedByOrder().asReversed()

    override fun handle(exchange: ServerCommandExchange<*>, aggregateMetadata: AggregateMetadata<*, *>): Mono<Void> {
        var handling = Mono.defer { processThenPublish(exchange, aggregateMetadata) }
        if (commandWaitNotifier != null) {
            handling = handling.thenNotifyAndForget(commandWaitNotifier, CommandStage.PROCESSED, exchange)
        }
        for (instrumentation in instrumentationsInnermostFirst) {
            handling = instrumentation.around(exchange, handling)
        }
        return handling.onErrorResume {
            exchange.setError(it)
            errorHandler.handle(exchange, it)
        }
    }

    private fun processThenPublish(
        exchange: ServerCommandExchange<*>,
        aggregateMetadata: AggregateMetadata<*, *>
    ): Mono<Void> {
        exchange.setServiceProvider(serviceProvider)
        val aggregateProcessor = aggregateProcessorFactory.create(
            aggregateId = exchange.message.aggregateId,
            aggregateMetadata = aggregateMetadata,
        )
        var committed: DomainEventStream? = null
        return checkRequestId(exchange)
            .then(Mono.defer { aggregateProcessor.process(exchange) })
            .checkpoint {
                "[${aggregateProcessor.aggregateId}] Process Command[${exchange.message.id}] [DefaultCommandHandler]"
            }
            .doOnNext { committed = it }
            .finallyAck(exchange)
            .then(Mono.defer { committed?.let { publish(exchange, it) } ?: Mono.empty() })
    }

    private fun checkRequestId(exchange: ServerCommandExchange<*>): Mono<Void> {
        val checker = requestIdChecker ?: return Mono.empty()
        val command = exchange.message
        return checker.check(command.aggregateId, command.requestId).flatMap { passed ->
            if (passed) {
                Mono.empty()
            } else {
                Mono.error(DuplicateRequestIdException(command.aggregateId, command.requestId))
            }
        }
    }

    private fun publish(exchange: ServerCommandExchange<*>, eventStream: DomainEventStream): Mono<Void> {
        val sendDomainEventStream = domainEventBus?.send(eventStream)
            ?.checkpoint { "Send Message[${eventStream.id}] [DefaultCommandHandler]" }
            ?: Mono.empty()
        return sendDomainEventStream.then(Mono.defer { sendStateEvent(exchange, eventStream) })
    }

    private fun sendStateEvent(exchange: ServerCommandExchange<*>, eventStream: DomainEventStream): Mono<Void> {
        val bus = stateEventBus ?: return Mono.empty()
        val state = exchange.getCommandAggregate<Any, Any>()?.state
        if (state == null) {
            log.warn { "No state to send a state event for DomainEventStream[${eventStream.id}]." }
            return Mono.empty()
        }
        // A state that failed to apply the stream stays at its previous version and is not published (B9).
        if (!state.initialized || state.version != eventStream.version) {
            return Mono.empty()
        }
        return bus.send(eventStream.copy().toStateEvent(state))
            .checkpoint { "Send Message[${eventStream.id}] StateEvent [DefaultCommandHandler]" }
            .logErrorResume()
    }
}
