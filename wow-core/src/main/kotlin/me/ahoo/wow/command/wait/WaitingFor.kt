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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.infra.sink.cancelled
import me.ahoo.wow.infra.sink.concurrent
import me.ahoo.wow.infra.sink.terminated
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import reactor.core.publisher.Sinks
import reactor.core.publisher.Sinks.EmitFailureHandler
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Abstract base class for wait strategies that wait for specific command processing stages.
 * Provides common functionality for managing wait signals, completion, and error handling.
 * Subclasses must implement the logic for determining which signals are relevant.
 */
abstract class WaitingFor : WaitStrategy {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    protected val waitSignalSink: Sinks.Many<WaitSignal> =
        Sinks.unsafe().many().unicast().onBackpressureBuffer<WaitSignal>().concurrent()
    override val cancelled: Boolean
        get() = waitSignalSink.cancelled

    override val terminated: Boolean
        get() = waitSignalSink.terminated

    override val supportVoidCommand: Boolean = false

    protected var onFinallyHook: AtomicReference<Consumer<SignalType>> = AtomicReference(EmptyOnFinally)

    @Suppress("TooGenericExceptionCaught")
    protected fun safeDoFinally(signalType: SignalType) {
        val currentHook = onFinallyHook.get()
        try {
            currentHook.accept(signalType)
        } catch (error: Throwable) {
            log.error(error) {
                "Finally hook execution failed"
            }
        }
    }

    override fun waiting(): Flux<WaitSignal> = waitSignalSink.asFlux().doFinally(this::safeDoFinally)

    override fun waitingLast(): Mono<WaitSignal> {
        return waiting().collectList().mapNotNull { signals ->
            if (signals.isEmpty()) {
                return@mapNotNull null
            }
            signals.sortBy { it.signalTime }
            val result: MutableMap<String, Any> = mutableMapOf()
            signals.forEach { signal ->
                result.putAll(signal.result)
            }
            signals.last().copyResult(result)
        }
    }

    protected fun tryEmit(emit: () -> Unit): Boolean {
        if (completed) {
            log.warn {
                "WaitingFor is terminated or cancelled, ignore emit."
            }
            return false
        }
        emit()
        return true
    }

    /**
     * Determines if the given wait signal represents a prerequisite stage.
     * Prerequisite signals are those that must complete before this wait strategy
     * can consider its waiting condition satisfied.
     *
     * @param signal The wait signal to evaluate.
     * @return true if this is a prerequisite signal, false otherwise.
     */
    abstract fun isPreviousSignal(signal: WaitSignal): Boolean

    protected open fun nextSignal(signal: WaitSignal) {
        tryEmit {
            waitSignalSink.emitNext(signal, EmitFailureHandler.FAIL_FAST)
            /**
             * fail fast
             */
            if (signal.succeeded.not() && isPreviousSignal(signal)) {
                complete()
            }
        }
    }

    override fun error(throwable: Throwable) {
        tryEmit {
            waitSignalSink.emitError(throwable, EmitFailureHandler.FAIL_FAST)
        }
    }

    override fun complete() {
        tryEmit {
            waitSignalSink.emitComplete(EmitFailureHandler.FAIL_FAST)
        }
    }

    override fun onFinally(doFinally: Consumer<SignalType>) {
        check(this.onFinallyHook.compareAndSet(EmptyOnFinally, doFinally)) {
            "Finally hook already set [${this.onFinallyHook.get()}]"
        }
    }

    object EmptyOnFinally : Consumer<SignalType> {
        override fun accept(t: SignalType) = Unit
    }
}
