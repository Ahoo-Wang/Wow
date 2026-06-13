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

package me.ahoo.wow.command.wait

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import reactor.core.publisher.Sinks
import reactor.util.concurrent.Queues

interface WaitHandle : WaitCommandIdCapable {
    val plan: WaitPlan
    fun next(signal: WaitSignal): Boolean
    fun error(throwable: Throwable)
    fun cancel()
}

interface WaitLastHandle : WaitHandle {
    /**
     * Awaits the final wait signal.
     *
     * A wait handle is designed for a single subscriber. Signals may arrive
     * before subscription, and the final result is retained for awaiting.
     */
    fun await(): Mono<WaitSignal>
}

interface WaitStreamHandle : WaitHandle {
    /**
     * Streams accepted wait signals.
     *
     * A wait handle is single-subscriber. Signals may arrive before subscription
     * and are buffered for the first subscriber only.
     */
    fun stream(): Flux<WaitSignal>
}

const val DEFAULT_WAIT_STREAM_QUEUE_LINK_SIZE: Int = 16

internal class DefaultWaitLastHandle(
    override val plan: WaitPlan,
    private val onTerminate: () -> Unit,
) : WaitLastHandle {
    override val waitCommandId: String = plan.waitCommandId
    private val sink = Sinks.one<WaitSignal>()
    private val lock = Any()
    private val state: WaitState = createWaitState(plan)
    private var terminated: Boolean = false

    override fun await(): Mono<WaitSignal> =
        sink.asMono()
            .doFinally { terminateOnSubscriberCancel(it) }

    private fun terminateOnSubscriberCancel(signalType: SignalType) {
        if (signalType == SignalType.CANCEL) {
            cancel()
        }
    }

    override fun next(signal: WaitSignal): Boolean {
        var shouldTerminate = false
        val accepted = synchronized(lock) {
            if (terminated) {
                return false
            }
            val transition = state.next(signal)
            if (transition.completed) {
                val emitResult = transition.finalSignal?.let {
                    sink.tryEmitValue(it)
                } ?: sink.tryEmitEmpty()
                emitResult.requireTerminalEmission()
                if (!terminated) {
                    terminated = true
                    shouldTerminate = true
                }
            }
            transition.acceptedSignal != null
        }
        if (shouldTerminate) {
            onTerminate()
        }
        return accepted
    }

    override fun error(throwable: Throwable) {
        terminateWithSink {
            sink.tryEmitError(throwable).also {
                it.requireTerminalEmission(throwable)
            }
        }
    }

    override fun cancel() {
        terminateWithSink {
            sink.tryEmitEmpty().also {
                it.requireTerminalEmission()
            }
        }
    }

    private fun terminateWithSink(emit: () -> Unit) {
        val shouldTerminate = synchronized(lock) {
            if (terminated) {
                false
            } else {
                terminated = true
                true
            }
        }
        if (shouldTerminate) {
            emit()
            onTerminate()
        }
    }
}

internal class DefaultWaitStreamHandle(
    override val plan: WaitPlan,
    private val onTerminate: () -> Unit,
    queueLinkSize: Int = DEFAULT_WAIT_STREAM_QUEUE_LINK_SIZE,
) : WaitStreamHandle {
    override val waitCommandId: String = plan.waitCommandId
    private val sink = unicastStreamSink<WaitSignal>(queueLinkSize)
    private val lock = Any()
    private val state: WaitState = createWaitState(plan)
    private var terminated: Boolean = false

    override fun stream(): Flux<WaitSignal> =
        sink.asFlux()
            .doFinally { terminateOnSubscriberCancel(it) }

    private fun terminateOnSubscriberCancel(signalType: SignalType) {
        if (signalType == SignalType.CANCEL) {
            cancel()
        }
    }

    override fun next(signal: WaitSignal): Boolean {
        var shouldTerminate = false
        var emissionException: Sinks.EmissionException? = null
        val accepted = synchronized(lock) {
            if (terminated) {
                return false
            }
            val transition = state.next(signal)
            transition.acceptedSignal?.let {
                val emitResult = sink.tryEmitNext(it)
                if (emitResult != Sinks.EmitResult.OK) {
                    terminated = true
                    shouldTerminate = true
                    emissionException = emitResult.toEmissionException()
                    return@synchronized true
                }
            }
            if (transition.completed) {
                sink.tryEmitComplete().requireTerminalEmission()
                if (!terminated) {
                    terminated = true
                    shouldTerminate = true
                }
            }
            transition.acceptedSignal != null
        }
        emissionException?.let {
            sink.tryEmitError(it).requireTerminalEmission(it)
        }
        if (shouldTerminate) {
            onTerminate()
        }
        emissionException?.let {
            throw it
        }
        return accepted
    }

    override fun error(throwable: Throwable) {
        terminateWithSink {
            sink.tryEmitError(throwable).also {
                it.requireTerminalEmission(throwable)
            }
        }
    }

    override fun cancel() {
        terminateWithSink {
            sink.tryEmitComplete().also {
                it.requireTerminalEmission()
            }
        }
    }

    private fun terminateWithSink(emit: () -> Unit) {
        val shouldTerminate = synchronized(lock) {
            if (terminated) {
                false
            } else {
                terminated = true
                true
            }
        }
        if (shouldTerminate) {
            emit()
            onTerminate()
        }
    }
}

private fun <T : Any> unicastStreamSink(queueLinkSize: Int): Sinks.Many<T> {
    require(queueLinkSize > 0) {
        "Wait stream queueLinkSize must be greater than 0."
    }
    return Sinks.many().unicast().onBackpressureBuffer(Queues.unbounded<T>(queueLinkSize).get())
}

private fun Sinks.EmitResult.requireEmission() {
    if (this == Sinks.EmitResult.OK) {
        return
    }
    orThrow()
}

private fun Sinks.EmitResult.toEmissionException(): Sinks.EmissionException =
    Sinks.EmissionException(this)

private fun Sinks.EmitResult.requireTerminalEmission(cause: Throwable? = null) {
    if (this == Sinks.EmitResult.OK ||
        this == Sinks.EmitResult.FAIL_TERMINATED ||
        this == Sinks.EmitResult.FAIL_CANCELLED
    ) {
        return
    }
    if (cause == null) {
        orThrow()
    } else {
        orThrowWithCause(cause)
    }
}
