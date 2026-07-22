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

package me.ahoo.wow.infra.sink

import org.reactivestreams.Subscription
import reactor.core.CoreSubscriber
import reactor.core.Exceptions
import reactor.core.Scannable
import reactor.core.publisher.Flux
import reactor.core.publisher.Operators
import reactor.core.publisher.SignalType
import reactor.core.publisher.Sinks
import reactor.util.concurrent.Queues
import reactor.util.context.Context
import java.util.Queue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream

private const val TERMINAL_CLAIMED = Long.MIN_VALUE
private const val TERMINAL_DELEGATED = 1L shl 62
private const val ACTIVE_MASK = TERMINAL_DELEGATED - 1

internal class MpscUnicastManySink<T : Any> private constructor(
    private val sink: Sinks.Many<T>,
) : Sinks.Many<T> {
    private constructor(queue: Queue<T>) : this(
        Sinks.unsafe()
            .many()
            .unicast()
            .onBackpressureBuffer(queue),
    )

    private val state = AtomicLong()
    private val terminalSignal = AtomicReference<TerminalSignal?>()
    private val flux = OpaqueFlux(sink.asFlux())

    companion object {
        fun <T : Any> create(): MpscUnicastManySink<T> =
            MpscUnicastManySink(Queues.unboundedMultiproducer<T>().get())
    }

    override fun tryEmitNext(t: T): Sinks.EmitResult {
        if (!tryAcquireNext()) {
            return Sinks.EmitResult.FAIL_TERMINATED
        }
        return try {
            sink.tryEmitNext(t)
        } finally {
            releaseNext()
        }
    }

    override fun tryEmitComplete(): Sinks.EmitResult =
        tryClaimTerminal(TerminalSignal.Complete)

    override fun tryEmitError(error: Throwable): Sinks.EmitResult =
        tryClaimTerminal(TerminalSignal.Error(error))

    private fun tryAcquireNext(): Boolean {
        while (true) {
            val current = state.get()
            if (current and TERMINAL_CLAIMED != 0L) {
                return false
            }
            check(current and ACTIVE_MASK != ACTIVE_MASK) {
                "MPSC unicast sink active admission count exhausted."
            }
            if (state.compareAndSet(current, current + 1)) {
                return true
            }
        }
    }

    private fun releaseNext() {
        val released = state.decrementAndGet()
        check(released and ACTIVE_MASK != ACTIVE_MASK) {
            "MPSC unicast sink active admission underflow."
        }
        if (released == TERMINAL_CLAIMED) {
            normalizeTerminalResult(delegateTerminal())
        }
    }

    private fun tryClaimTerminal(signal: TerminalSignal): Sinks.EmitResult {
        while (true) {
            val current = state.get()
            if (current and TERMINAL_CLAIMED != 0L) {
                return Sinks.EmitResult.FAIL_TERMINATED
            }
            if (sink.scanUnsafe(Scannable.Attr.CANCELLED) == true) {
                return Sinks.EmitResult.FAIL_CANCELLED
            }
            if (state.compareAndSet(current, current or TERMINAL_CLAIMED)) {
                break
            }
        }

        terminalSignal.set(signal)
        return normalizeTerminalResult(delegateTerminal())
    }

    private fun delegateTerminal(): Sinks.EmitResult? {
        val signal = terminalSignal.get() ?: return null
        if (!state.compareAndSet(
                TERMINAL_CLAIMED,
                TERMINAL_CLAIMED or TERMINAL_DELEGATED,
            )
        ) {
            return null
        }
        return when (signal) {
            TerminalSignal.Complete -> sink.tryEmitComplete()
            is TerminalSignal.Error -> sink.tryEmitError(signal.error)
        }
    }

    private fun normalizeTerminalResult(result: Sinks.EmitResult?): Sinks.EmitResult =
        when (result) {
            null,
            Sinks.EmitResult.OK,
            Sinks.EmitResult.FAIL_CANCELLED,
            -> Sinks.EmitResult.OK

            else -> throw Sinks.EmissionException(
                result,
                "Factory-owned MPSC unicast delegate rejected a claimed terminal with [$result].",
            )
        }

    override fun emitNext(t: T, failureHandler: Sinks.EmitFailureHandler) {
        while (true) {
            val emitResult = tryEmitNext(t)
            if (emitResult.isSuccess) {
                return
            }
            if (failureHandler.onEmitFailure(SignalType.ON_NEXT, emitResult)) {
                continue
            }
            when (emitResult) {
                Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER -> return
                Sinks.EmitResult.FAIL_OVERFLOW -> {
                    Operators.onDiscard(t, currentContext())
                    emitError(
                        Exceptions.failWithOverflow("Backpressure overflow during Sinks.Many#emitNext"),
                        failureHandler,
                    )
                    return
                }

                Sinks.EmitResult.FAIL_CANCELLED -> {
                    Operators.onDiscard(t, currentContext())
                    return
                }

                Sinks.EmitResult.FAIL_TERMINATED -> {
                    Operators.onNextDropped(t, currentContext())
                    return
                }

                Sinks.EmitResult.FAIL_NON_SERIALIZED -> throw nonSerialized(emitResult)
                Sinks.EmitResult.OK -> return
            }
        }
    }

    override fun emitComplete(failureHandler: Sinks.EmitFailureHandler) {
        while (true) {
            val emitResult = tryEmitComplete()
            if (emitResult.isSuccess) {
                return
            }
            if (failureHandler.onEmitFailure(SignalType.ON_COMPLETE, emitResult)) {
                continue
            }
            when (emitResult) {
                Sinks.EmitResult.FAIL_NON_SERIALIZED -> throw nonSerialized(emitResult)
                Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER,
                Sinks.EmitResult.FAIL_OVERFLOW,
                Sinks.EmitResult.FAIL_CANCELLED,
                Sinks.EmitResult.FAIL_TERMINATED,
                Sinks.EmitResult.OK,
                -> return
            }
        }
    }

    override fun emitError(error: Throwable, failureHandler: Sinks.EmitFailureHandler) {
        while (true) {
            val emitResult = tryEmitError(error)
            if (emitResult.isSuccess) {
                return
            }
            if (failureHandler.onEmitFailure(SignalType.ON_ERROR, emitResult)) {
                continue
            }
            when (emitResult) {
                Sinks.EmitResult.FAIL_TERMINATED -> {
                    Operators.onErrorDropped(error, currentContext())
                    return
                }

                Sinks.EmitResult.FAIL_NON_SERIALIZED -> throw nonSerialized(emitResult)
                Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER,
                Sinks.EmitResult.FAIL_OVERFLOW,
                Sinks.EmitResult.FAIL_CANCELLED,
                Sinks.EmitResult.OK,
                -> return
            }
        }
    }

    private fun nonSerialized(emitResult: Sinks.EmitResult): Sinks.EmissionException =
        Sinks.EmissionException(
            emitResult,
            "Spec. Rule 1.3 - onSubscribe, onNext, onError and onComplete " +
                "signaled to a Subscriber MUST be signaled serially.",
        )

    private fun currentContext(): Context =
        (sink.scanUnsafe(Scannable.Attr.ACTUAL) as? CoreSubscriber<*>)?.currentContext()
            ?: Context.empty()

    override fun currentSubscriberCount(): Int = sink.currentSubscriberCount()

    override fun asFlux(): Flux<T> = flux

    override fun inners(): Stream<out Scannable> = sink.inners()

    override fun scanUnsafe(key: Scannable.Attr<*>): Any? =
        when (key) {
            Scannable.Attr.TERMINATED -> state.get() and TERMINAL_CLAIMED != 0L
            Scannable.Attr.ERROR -> (terminalSignal.get() as? TerminalSignal.Error)?.error
            else -> sink.scanUnsafe(key)
        }

    private sealed interface TerminalSignal {
        data object Complete : TerminalSignal

        class Error(val error: Throwable) : TerminalSignal
    }
}

internal fun <T : Any> mpscUnicastManySink(): MpscUnicastManySink<T> =
    MpscUnicastManySink.create()

internal fun <T : Any> Sinks.Many<T>.prepareConcurrentSink(): Sinks.Many<T> =
    if (this is MpscUnicastManySink<*>) {
        this
    } else {
        concurrent()
    }

private class OpaqueFlux<T : Any>(
    private val source: Flux<T>,
) : Flux<T>() {
    override fun subscribe(actual: CoreSubscriber<in T>) {
        source.subscribe(OpaqueSubscriber(actual))
    }
}

private class OpaqueSubscriber<T : Any>(
    private val actual: CoreSubscriber<in T>,
) : CoreSubscriber<T>, Scannable {
    override fun currentContext(): Context = actual.currentContext()

    override fun onSubscribe(subscription: Subscription) {
        actual.onSubscribe(OpaqueSubscription(subscription))
    }

    override fun onNext(value: T) {
        actual.onNext(value)
    }

    override fun onError(throwable: Throwable) {
        actual.onError(throwable)
    }

    override fun onComplete() {
        actual.onComplete()
    }

    override fun scanUnsafe(key: Scannable.Attr<*>): Any? =
        when (key) {
            Scannable.Attr.ACTUAL -> actual
            else -> null
        }
}

private class OpaqueSubscription(
    private val delegate: Subscription,
) : Subscription {
    override fun request(elements: Long) {
        delegate.request(elements)
    }

    override fun cancel() {
        delegate.cancel()
    }
}
