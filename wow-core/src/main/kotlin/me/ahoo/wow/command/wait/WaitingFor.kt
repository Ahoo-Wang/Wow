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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import reactor.core.publisher.Sinks
import reactor.util.concurrent.Queues
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import kotlin.concurrent.withLock

/**
 * Abstract base class for wait strategies that wait for specific command processing stages.
 * Provides common functionality for managing wait signals, completion, and error handling.
 * Subclasses must implement the logic for determining which signals are relevant.
 *
 * Signals are buffered in a small list until a subscriber materializes, so the dominant
 * `waitingLast()` path never allocates a Flux sink: the final signal is computed directly
 * from the buffered signals when the strategy completes. A Flux sink is materialized lazily
 * only when `waiting()` is subscribed, replaying any buffered signals.
 * At most one subscriber is supported across `waiting()` and `waitingLast()`.
 */
abstract class WaitingFor : WaitStrategy {
    companion object {
        private val log = KotlinLogging.logger {}
        private const val FLUX_BUFFER_LINK_SIZE = 8
        private const val SIGNAL_BUFFER_CAPACITY = 4
    }

    private val lock = ReentrantLock()

    /**
     * Signals received before a subscriber materializes, and the source for the final merged
     * signal of `waitingLast()`. Guarded by [lock].
     */
    private var bufferedSignals: MutableList<WaitSignal>? = null
    private var fluxSink: Sinks.Many<WaitSignal>? = null
    private var monoSink: Sinks.One<WaitSignal>? = null
    private var subscribed: Boolean = false
    private var terminalError: Throwable? = null

    @Volatile
    private var terminatedFlag = false

    @Volatile
    private var cancelledFlag = false

    @Volatile
    private var onFinallyHook: Consumer<SignalType> = EmptyOnFinally

    override val cancelled: Boolean
        get() = cancelledFlag

    override val terminated: Boolean
        get() = terminatedFlag

    override val supportVoidCommand: Boolean = false

    @Suppress("TooGenericExceptionCaught")
    protected fun safeDoFinally(signalType: SignalType) {
        if (signalType == SignalType.CANCEL) {
            cancelledFlag = true
        }
        val currentHook = onFinallyHook
        try {
            currentHook.accept(signalType)
        } catch (error: Throwable) {
            log.error(error) {
                "Finally hook execution failed"
            }
        }
    }

    override fun waiting(): Flux<WaitSignal> {
        return Flux.defer {
            materializeFlux()
        }.doFinally(this::safeDoFinally)
    }

    override fun waitingLast(): Mono<WaitSignal> {
        return Mono.defer {
            materializeMono()
        }.doFinally(this::safeDoFinally)
    }

    private fun materializeFlux(): Flux<WaitSignal> {
        lock.withLock {
            if (subscribed) {
                return Flux.error(IllegalStateException("WaitingFor allows only a single Subscriber"))
            }
            subscribed = true
            val sink = Sinks.unsafe().many().unicast()
                .onBackpressureBuffer(Queues.unbounded<WaitSignal>(FLUX_BUFFER_LINK_SIZE).get())
            bufferedSignals?.forEach {
                sink.tryEmitNext(it)
            }
            bufferedSignals = null
            val error = terminalError
            if (error != null) {
                sink.tryEmitError(error)
            } else if (terminatedFlag) {
                sink.tryEmitComplete()
            } else {
                fluxSink = sink
            }
            return sink.asFlux()
        }
    }

    private fun materializeMono(): Mono<WaitSignal> {
        lock.withLock {
            if (subscribed) {
                return Mono.error(IllegalStateException("WaitingFor allows only a single Subscriber"))
            }
            subscribed = true
            val error = terminalError
            if (error != null) {
                return Mono.error(error)
            }
            if (terminatedFlag) {
                val finalSignal = finalSignal()
                bufferedSignals = null
                return Mono.justOrEmpty(finalSignal)
            }
            val sink = Sinks.unsafe().one<WaitSignal>()
            monoSink = sink
            return sink.asMono()
        }
    }

    /**
     * Computes the final signal emitted by `waitingLast()`: the signal resolved by
     * [resolveLastSignal] carrying the result entries merged from every buffered signal
     * in arrival order. Caller must hold [lock].
     */
    private fun finalSignal(): WaitSignal? {
        val signals = bufferedSignals
        if (signals.isNullOrEmpty()) {
            return null
        }
        if (signals.size == 1) {
            return signals[0]
        }
        var merged: MutableMap<String, Any>? = null
        signals.forEach { signal ->
            if (signal.result.isNotEmpty()) {
                val target = merged ?: LinkedHashMap<String, Any>().also { merged = it }
                target.putAll(signal.result)
            }
        }
        val last = resolveLastSignal(signals)
        val mergedResult = merged ?: return last
        return last.copyResult(mergedResult)
    }

    /**
     * Resolves which buffered signal `waitingLast()` should emit. Defaults to the signal with
     * the greatest [WaitSignal.signalTime], preferring the later arrival on ties.
     *
     * @param signals The non-empty list of buffered signals in arrival order.
     * @return The signal whose fields (besides the merged result) back the final signal.
     */
    protected open fun resolveLastSignal(signals: List<WaitSignal>): WaitSignal {
        var last = signals[0]
        for (index in 1 until signals.size) {
            val signal = signals[index]
            if (signal.signalTime >= last.signalTime) {
                last = signal
            }
        }
        return last
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
        lock.withLock {
            if (completed) {
                logIgnoreEmit()
                return
            }
            val sink = fluxSink
            if (sink != null) {
                sink.tryEmitNext(signal)
            } else {
                val buffer = bufferedSignals ?: ArrayList<WaitSignal>(SIGNAL_BUFFER_CAPACITY).also {
                    bufferedSignals = it
                }
                buffer.add(signal)
            }
            /**
             * fail fast
             */
            if (signal.succeeded.not() && isPreviousSignal(signal)) {
                doComplete()
            }
        }
    }

    override fun error(throwable: Throwable) {
        lock.withLock {
            if (completed) {
                logIgnoreEmit()
                return
            }
            terminatedFlag = true
            terminalError = throwable
            fluxSink?.let {
                it.tryEmitError(throwable)
                fluxSink = null
                return
            }
            monoSink?.let {
                it.tryEmitError(throwable)
                monoSink = null
            }
        }
    }

    override fun complete() {
        lock.withLock {
            if (completed) {
                logIgnoreEmit()
                return
            }
            doComplete()
        }
    }

    /**
     * Terminates the strategy and delivers the terminal signal to the materialized sink,
     * if any. Caller must hold [lock] and have verified the strategy is not completed.
     */
    private fun doComplete() {
        terminatedFlag = true
        fluxSink?.let {
            it.tryEmitComplete()
            fluxSink = null
            return
        }
        monoSink?.let {
            val finalSignal = finalSignal()
            bufferedSignals = null
            if (finalSignal == null) {
                it.tryEmitEmpty()
            } else {
                it.tryEmitValue(finalSignal)
            }
            monoSink = null
        }
    }

    private fun logIgnoreEmit() {
        log.warn {
            "WaitingFor is terminated or cancelled, ignore emit."
        }
    }

    override fun onFinally(doFinally: Consumer<SignalType>) {
        lock.withLock {
            check(onFinallyHook === EmptyOnFinally) {
                "Finally hook already set [$onFinallyHook]"
            }
            onFinallyHook = doFinally
        }
    }

    object EmptyOnFinally : Consumer<SignalType> {
        override fun accept(t: SignalType) = Unit
    }
}
