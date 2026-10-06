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
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedTypedAggregate
import me.ahoo.wow.command.AGGREGATE_VERSION_KEY
import me.ahoo.wow.command.COMMAND_INVOKE_RESULT_KEY
import me.ahoo.wow.command.EVENT_STREAM_KEY
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.exception.recoverable
import me.ahoo.wow.messaging.handler.COMMAND_RESULT_KEY
import me.ahoo.wow.messaging.handler.FUNCTION_KEY
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration
import java.util.Optional

internal class RetryableAggregateProcessor<C : Any, S : Any>(
    override val aggregateId: AggregateId,
    private val aggregateMetadata: AggregateMetadata<C, S>,
    private val aggregateFactory: StateAggregateFactory,
    private val stateAggregateRepository: StateAggregateRepository,
    private val commandAggregateFactory: CommandAggregateFactory,
    private val maxRetries: Long = DEFAULT_MAX_RETRIES
) : AggregateProcessor<C>, NamedTypedAggregate<C> by aggregateMetadata.command {
    companion object {
        private val log = KotlinLogging.logger {}
        const val DEFAULT_MAX_RETRIES = 3L
        private val MIN_BACKOFF = Duration.ofMillis(500)
    }

    override val processorName: String = RetryableAggregateProcessor::class.simpleName!!

    /**
     * Processes [exchange], retrying recoverable failures with backoff.
     *
     * Every attempt starts from the exchange as it was before the first one (no error, event stream, aggregate
     * version, command results or command aggregate of a failed attempt). The retry decision uses the processing
     * failure, not what `@OnError` makes of it. The aggregate's `@OnError` function runs once, after the final
     * failure, on the most recently loaded aggregate: the final attempt's, or an earlier attempt's when the final one
     * failed before loading. It does not run when no attempt loaded an aggregate, nor for a [CommandAggregate] that is
     * not a [SimpleCommandAggregate] (its own `process` handles its errors). `@OnError` always sees committed state:
     * when that aggregate applied events that were not stored, it runs on the aggregate loaded again instead.
     */
    override fun process(exchange: ServerCommandExchange<*>): Mono<DomainEventStream> {
        val initialState = ExchangeAttemptState.capture(exchange)
        return Mono.defer {
            // The aggregate whose `@OnError` handles the final failure; per subscription.
            var errorHandlingAggregate: SimpleCommandAggregate<C, *>? = null
            val process = Mono.defer {
                initialState.restore(exchange)
                loadCommandAggregate(exchange)
            }.flatMap {
                if (it is SimpleCommandAggregate<C, *>) {
                    errorHandlingAggregate = it
                    it.processAttempt(exchange)
                } else {
                    errorHandlingAggregate = null
                    it.process(exchange)
                }
            }
            val retried = if (maxRetries == 0L) process else retryRecoverable(process)
            retried.onErrorResume { finalError ->
                val commandAggregate = errorHandlingAggregate ?: return@onErrorResume Mono.error(finalError)
                handleFinalError(commandAggregate, exchange, finalError)
            }
        }
    }

    /** Retries [process] on recoverable failures, [maxRetries] times with backoff. */
    private fun retryRecoverable(process: Mono<DomainEventStream>): Mono<DomainEventStream> =
        process.onErrorResume { failure ->
            var firstFailure = true
            Mono.defer {
                if (firstFailure) {
                    firstFailure = false
                    // Replay the failure so Reactor preserves the first backoff and the full retry budget.
                    Mono.error(failure)
                } else {
                    process
                }
            }.retryWhen(
                Retry.backoff(maxRetries, MIN_BACKOFF)
                    .filter {
                        it.recoverable == RecoverableType.RECOVERABLE
                    }.doBeforeRetry {
                        log.warn(it.failure()) {
                            "[BeforeRetry] $aggregateId totalRetries[${it.totalRetries()}]."
                        }
                    }
            )
        }

    /** A command aggregate on the committed state: a new one for a create command, else the loaded one. */
    private fun loadCommandAggregate(exchange: ServerCommandExchange<*>): Mono<CommandAggregate<C, *>> {
        val state = if (exchange.message.isCreate) {
            aggregateFactory.createAsMono(aggregateMetadata.state, exchange.message.aggregateId)
        } else {
            stateAggregateRepository.load(aggregateId, aggregateMetadata.state)
        }
        return state.map { commandAggregateFactory.create(aggregateMetadata, it) }
    }

    /**
     * Runs the `@OnError` function with the processing failure (unwrapped from a retry exhaustion), on the committed
     * state. The final error stays [finalError] unless the error function replaced it.
     *
     * When [commandAggregate] is [discarded][SimpleCommandAggregate.discarded] (it applied events that were not
     * stored), the exchange no longer holds it, so neither the error handler, an instrumentation nor a test sees its
     * state; and when the command has an `@OnError` function, the aggregate is loaded again for it (a create gets a new
     * one from the factory). When that load fails, `@OnError` is skipped and [finalError] propagates, with the load
     * failure attached as suppressed.
     */
    private fun handleFinalError(
        commandAggregate: SimpleCommandAggregate<C, *>,
        exchange: ServerCommandExchange<*>,
        finalError: Throwable
    ): Mono<DomainEventStream> {
        val failure = if (Exceptions.isRetryExhausted(finalError)) finalError.cause ?: finalError else finalError
        if (!commandAggregate.discarded) {
            return runErrorFunction(commandAggregate, exchange, failure, finalError)
        }
        // The discarded aggregate holds events the store does not: nothing may observe it.
        exchange.removeAttribute(COMMAND_AGGREGATE_KEY)
        if (!commandAggregate.hasErrorFunction(exchange.message.body.javaClass)) {
            // No user code runs: record the error without loading anything.
            return commandAggregate.handleError(exchange, failure).onErrorMap {
                if (it === failure) finalError else it
            }
        }
        return loadCommandAggregate(exchange)
            .map { Optional.ofNullable(it as? SimpleCommandAggregate<C, *>) }
            .onErrorResume { loadError ->
                if (loadError !== failure) {
                    failure.addSuppressed(loadError)
                }
                exchange.setError(failure)
                log.error(failure) {
                    "@OnError skipped: committed state could not be loaded for $aggregateId after command " +
                        "[${exchange.message.id}] failed."
                }
                Mono.just(Optional.empty())
            }
            .flatMap { reloaded ->
                if (reloaded.isPresent) {
                    runErrorFunction(reloaded.get(), exchange, failure, finalError)
                } else {
                    Mono.error(finalError)
                }
            }
    }

    private fun runErrorFunction(
        commandAggregate: SimpleCommandAggregate<C, *>,
        exchange: ServerCommandExchange<*>,
        failure: Throwable,
        finalError: Throwable
    ): Mono<DomainEventStream> {
        // When the final attempt failed before loading, the exchange holds no aggregate; give @OnError the one it runs on.
        exchange.setCommandAggregate(commandAggregate)
        return commandAggregate.handleError(exchange, failure).onErrorMap {
            if (it === failure) finalError else it
        }
    }
}

/** The exchange attributes one processing attempt writes, as they were before the first attempt. */
private class ExchangeAttemptState(private val values: Map<String, Any>) {
    companion object {
        private val ATTEMPT_KEYS = listOf(
            EVENT_STREAM_KEY,
            AGGREGATE_VERSION_KEY,
            COMMAND_INVOKE_RESULT_KEY,
            COMMAND_RESULT_KEY,
            COMMAND_AGGREGATE_KEY,
            FUNCTION_KEY,
        )

        fun capture(exchange: ServerCommandExchange<*>): ExchangeAttemptState {
            val values = HashMap<String, Any>(ATTEMPT_KEYS.size)
            ATTEMPT_KEYS.forEach { key ->
                exchange.getAttribute<Any>(key)?.let { values[key] = it }
            }
            return ExchangeAttemptState(values)
        }
    }

    fun restore(exchange: ServerCommandExchange<*>) {
        exchange.clearError()
        ATTEMPT_KEYS.forEach { key ->
            val value = values[key]
            if (value == null) {
                exchange.removeAttribute(key)
            } else {
                exchange.setAttribute(key, value)
            }
        }
    }
}
