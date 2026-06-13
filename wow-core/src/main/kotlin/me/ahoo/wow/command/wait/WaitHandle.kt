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

interface WaitHandle : WaitCommandIdCapable {
    val plan: WaitPlan
    fun next(signal: WaitSignal): Boolean
    fun error(throwable: Throwable)
    fun cancel()
}

interface WaitLastHandle : WaitHandle {
    fun await(): Mono<WaitSignal>
}

interface WaitStreamHandle : WaitHandle {
    fun stream(): Flux<WaitSignal>
}

internal class DefaultWaitLastHandle(
    override val plan: WaitPlan,
    private val reducer: WaitSignalReducer,
    private val onTerminate: () -> Unit,
) : WaitLastHandle {
    override val waitCommandId: String = plan.waitCommandId
    private val sink = Sinks.one<WaitSignal>()
    private val lock = Any()
    private var state: WaitReductionState = WaitReductionState.initial(plan)
    private var terminated: Boolean = false
    private var activeAwaitSubscribers: Int = 0

    override fun await(): Mono<WaitSignal> =
        sink.asMono()
            .doOnSubscribe { registerAwaitSubscriber() }
            .doFinally { unregisterAwaitSubscriber(it) }

    private fun registerAwaitSubscriber() {
        synchronized(lock) {
            activeAwaitSubscribers++
        }
    }

    private fun unregisterAwaitSubscriber(signalType: SignalType) {
        val shouldCancel = synchronized(lock) {
            activeAwaitSubscribers--
            if (signalType == SignalType.CANCEL && activeAwaitSubscribers == 0 && !terminated) {
                terminated = true
                true
            } else {
                false
            }
        }
        if (shouldCancel) {
            sink.tryEmitEmpty().requireTerminalEmission()
            onTerminate()
        }
    }

    override fun next(signal: WaitSignal): Boolean {
        var shouldTerminate = false
        val accepted = synchronized(lock) {
            if (terminated) {
                return false
            }
            val reduction = reducer.reduce(state, signal)
            state = reduction.state
            if (reduction.completed) {
                val emitResult = reduction.finalSignal?.let {
                    sink.tryEmitValue(it)
                } ?: sink.tryEmitEmpty()
                emitResult.requireTerminalEmission()
                if (!terminated) {
                    terminated = true
                    shouldTerminate = true
                }
            }
            reduction.acceptedSignal != null
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
    private val reducer: WaitSignalReducer,
    private val onTerminate: () -> Unit,
) : WaitStreamHandle {
    override val waitCommandId: String = plan.waitCommandId
    private val sink = Sinks.many().replay().all<WaitSignal>()
    private val lock = Any()
    private var state: WaitReductionState = WaitReductionState.initial(plan)
    private var terminated: Boolean = false

    override fun stream(): Flux<WaitSignal> =
        sink.asFlux()

    override fun next(signal: WaitSignal): Boolean {
        var shouldTerminate = false
        val accepted = synchronized(lock) {
            if (terminated) {
                return false
            }
            val reduction = reducer.reduce(state, signal)
            state = reduction.state
            reduction.acceptedSignal?.let {
                sink.tryEmitNext(it).requireEmission()
            }
            if (reduction.completed) {
                sink.tryEmitComplete().requireTerminalEmission()
                if (!terminated) {
                    terminated = true
                    shouldTerminate = true
                }
            }
            reduction.acceptedSignal != null
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

private fun Sinks.EmitResult.requireEmission() {
    if (this == Sinks.EmitResult.OK) {
        return
    }
    orThrow()
}

private fun Sinks.EmitResult.requireTerminalEmission(cause: Throwable? = null) {
    if (this == Sinks.EmitResult.OK || this == Sinks.EmitResult.FAIL_TERMINATED) {
        return
    }
    if (cause == null) {
        orThrow()
    } else {
        orThrowWithCause(cause)
    }
}
