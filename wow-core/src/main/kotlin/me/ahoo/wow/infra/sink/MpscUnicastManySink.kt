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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream

private const val TERMINAL_CLAIMED = Long.MIN_VALUE
private const val TERMINAL_DELEGATED = 1L shl 62
private const val CANCELLATION_REQUESTED = 1L shl 61
private const val CANCELLATION_DELEGATED = 1L shl 60
private const val TERMINAL_SETTLED = 1L shl 59
private const val CANCELLATION_SETTLED = 1L shl 58
private const val CLOSE_SETTLED = 1L shl 57
private const val ACTIVE_MASK = CLOSE_SETTLED - 1

internal interface CloseSettlementAware {
    val closeSettled: CompletableFuture<Unit>
}

/**
 * Factory-owned unicast sink with lock-free multi-producer admission.
 *
 * The low 57 state bits count admitted `onNext` calls. The remaining bits claim,
 * delegate and settle terminal or cancellation control signals. Claiming either
 * control signal rejects new values immediately, while physical delegation is
 * deferred until every already-admitted producer has returned. A final CAS marks
 * close settlement only after every control path delegated before that CAS has
 * returned. This preserves Reactive Streams signal ordering and prevents queue
 * cleanup from racing an MPSC offer, without holding a lock across downstream work.
 *
 * The delegate is intentionally hidden behind [OpaqueFlux] so subscribers cannot
 * expose or fuse directly with the factory-owned MPSC queue.
 */
internal class MpscUnicastManySink<T : Any> private constructor(
    private val sink: Sinks.Many<T>,
) : Sinks.Many<T>,
    CloseSettlementAware {
    private constructor(queue: Queue<T>) : this(
        Sinks.unsafe()
            .many()
            .unicast()
            .onBackpressureBuffer(queue),
    )

    private val state = AtomicLong()
    private val terminalSignal = AtomicReference<TerminalSignal?>()
    private val subscription = AtomicReference<Subscription?>()
    private val actualSubscriber = AtomicReference<CoreSubscriber<in T>?>()
    private val closeFailure = AtomicReference<Throwable?>()
    private val settlementLock = Any()
    private var downstreamOperations = 0
    override val closeSettled = CompletableFuture<Unit>()
    private val flux = OpaqueFlux(
        source = sink.asFlux(),
        registerActual = { actualSubscriber.compareAndSet(null, it) },
        registerSubscription = ::registerSubscription,
        requestCancellation = ::requestCancellation,
        isCancellationRequested = ::isCancellationRequested,
        beginDownstreamOperation = ::beginDownstreamOperation,
        endDownstreamOperation = ::endDownstreamOperation,
        scanAttribute = ::scanUnsafe,
        innerScannables = ::inners,
    )

    companion object {
        fun <T : Any> create(): MpscUnicastManySink<T> =
            MpscUnicastManySink(Queues.unboundedMultiproducer<T>().get())
    }

    override fun tryEmitNext(t: T): Sinks.EmitResult {
        val admissionFailure = tryAcquireNext()
        if (admissionFailure != null) {
            return admissionFailure
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

    private fun tryAcquireNext(): Sinks.EmitResult? {
        while (true) {
            val current = state.get()
            if (current and TERMINAL_CLAIMED != 0L) {
                return Sinks.EmitResult.FAIL_TERMINATED
            }
            if (current and CANCELLATION_REQUESTED != 0L) {
                return Sinks.EmitResult.FAIL_CANCELLED
            }
            check(current and ACTIVE_MASK != ACTIVE_MASK) {
                "MPSC unicast sink active admission count exhausted."
            }
            if (state.compareAndSet(current, current + 1)) {
                return null
            }
        }
    }

    private fun releaseNext() {
        val released = state.decrementAndGet()
        check(released and ACTIVE_MASK != ACTIVE_MASK) {
            "MPSC unicast sink active admission underflow."
        }
        if (released and ACTIVE_MASK == 0L) {
            normalizeTerminalResult(drainControlSignals())
        }
    }

    private fun tryClaimTerminal(signal: TerminalSignal): Sinks.EmitResult {
        while (true) {
            val current = state.get()
            if (current and TERMINAL_CLAIMED != 0L) {
                return Sinks.EmitResult.FAIL_TERMINATED
            }
            if (current and CANCELLATION_REQUESTED != 0L) {
                return Sinks.EmitResult.FAIL_CANCELLED
            }
            if (sink.scanUnsafe(Scannable.Attr.CANCELLED) == true) {
                return Sinks.EmitResult.FAIL_CANCELLED
            }
            if (state.compareAndSet(current, current or TERMINAL_CLAIMED)) {
                break
            }
        }

        terminalSignal.set(signal)
        return normalizeTerminalResult(drainControlSignals())
    }

    private fun registerSubscription(delegate: Subscription) {
        check(subscription.compareAndSet(null, delegate)) {
            "Factory-owned MPSC unicast sink registered more than one lifecycle subscription."
        }
        drainControlSignals()
    }

    private fun requestCancellation() {
        while (true) {
            val current = state.get()
            if (current and CLOSE_SETTLED != 0L) {
                /*
                 * A first subscriber can arrive after a terminal-only sink has
                 * already settled. Its cancellation is ordered after that close
                 * linearization point, but must still reach the raw subscription
                 * so Reactor clears buffered values and suppresses late terminal
                 * delivery.
                 */
                subscription.get()?.cancel()
                return
            }
            if (current and CANCELLATION_REQUESTED != 0L) {
                return
            }
            if (state.compareAndSet(current, current or CANCELLATION_REQUESTED)) {
                break
            }
        }
        normalizeTerminalResult(drainControlSignals())
    }

    private fun isCancellationRequested(): Boolean =
        state.get() and CANCELLATION_REQUESTED != 0L

    private fun drainControlSignals(): Sinks.EmitResult? {
        if (state.get() and ACTIVE_MASK != 0L) {
            return null
        }
        delegateCancellation()
        return delegateTerminal()
    }

    private fun delegateCancellation() {
        val delegate = subscription.get() ?: return
        while (true) {
            val current = state.get()
            if (!canDelegateCancellation(current)) {
                return
            }
            if (state.compareAndSet(current, current or CANCELLATION_DELEGATED)) {
                settleControl(CANCELLATION_SETTLED) {
                    delegate.cancel()
                }
                return
            }
        }
    }

    private fun delegateTerminal(): Sinks.EmitResult? {
        val signal = terminalSignal.get() ?: return null
        while (true) {
            val current = state.get()
            if (!canDelegateTerminal(current)) {
                return null
            }
            if (state.compareAndSet(current, current or TERMINAL_DELEGATED)) {
                break
            }
        }
        return settleControl(TERMINAL_SETTLED) {
            normalizeTerminalResult(
                when (signal) {
                    TerminalSignal.Complete -> sink.tryEmitComplete()
                    is TerminalSignal.Error -> sink.tryEmitError(signal.error)
                },
            )
        }
    }

    private fun beginDownstreamOperation() {
        synchronized(settlementLock) {
            downstreamOperations++
        }
    }

    private fun endDownstreamOperation() {
        val remaining = synchronized(settlementLock) {
            downstreamOperations--
            check(downstreamOperations >= 0) {
                "MPSC unicast sink downstream operation count underflow."
            }
            downstreamOperations
        }
        if (remaining == 0) {
            normalizeTerminalResult(drainControlSignals())
            tryCompleteCloseSettlement()
        }
    }

    private fun tryCompleteCloseSettlement() {
        synchronized(settlementLock) {
            if (closeSettled.isDone || downstreamOperations != 0) {
                return
            }
            completeCloseSettlementState()
        }
    }

    private fun completeCloseSettlementState() {
        while (true) {
            val current = state.get()
            if (!canSettleClose(current)) {
                return
            }
            if (!state.compareAndSet(current, current or CLOSE_SETTLED)) {
                continue
            }
            val failure = closeFailure.get()
            if (failure == null) {
                closeSettled.complete(Unit)
            } else {
                closeSettled.completeExceptionally(failure)
            }
            return
        }
    }

    private fun canDelegateCancellation(current: Long): Boolean {
        if (current and ACTIVE_MASK != 0L) return false
        if (current and CANCELLATION_REQUESTED == 0L) return false
        if (current and CANCELLATION_DELEGATED != 0L) return false
        return current and CLOSE_SETTLED == 0L
    }

    private fun canDelegateTerminal(current: Long): Boolean {
        if (current and ACTIVE_MASK != 0L) return false
        if (current and TERMINAL_CLAIMED == 0L) return false
        if (current and TERMINAL_DELEGATED != 0L) return false
        return current and CLOSE_SETTLED == 0L
    }

    private fun canSettleClose(current: Long): Boolean {
        if (current and CLOSE_SETTLED != 0L) return false
        if (current and (CANCELLATION_DELEGATED or TERMINAL_DELEGATED) == 0L) return false
        if (!isDelegationSettled(current, CANCELLATION_DELEGATED, CANCELLATION_SETTLED)) return false
        if (!isDelegationSettled(current, TERMINAL_DELEGATED, TERMINAL_SETTLED)) return false
        return true
    }

    private fun isDelegationSettled(current: Long, delegated: Long, settled: Long): Boolean {
        if (current and delegated == 0L) return true
        return current and settled != 0L
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <R> settleControl(settledState: Long, block: () -> R): R =
        try {
            block()
        } catch (error: Throwable) {
            recordCloseFailure(error)
            throw error
        } finally {
            state.getAndUpdate { current -> current or settledState }
            tryCompleteCloseSettlement()
        }

    private fun recordCloseFailure(error: Throwable) {
        while (true) {
            val current = closeFailure.get()
            if (current == null) {
                if (closeFailure.compareAndSet(null, error)) {
                    return
                }
                continue
            }
            if (current !== error) {
                synchronized(current) {
                    current.addSuppressed(error)
                }
            }
            return
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

    override fun inners(): Stream<out Scannable> {
        if (currentSubscriberCount() == 0) {
            return Stream.empty()
        }
        return Stream.of(Scannable.from(checkNotNull(actualSubscriber.get())))
    }

    override fun scanUnsafe(key: Scannable.Attr<*>): Any? =
        when (key) {
            Scannable.Attr.ACTUAL -> actualSubscriber.get() ?: sink.scanUnsafe(key)
            Scannable.Attr.TERMINATED -> state.get() and TERMINAL_CLAIMED != 0L
            Scannable.Attr.CANCELLED ->
                state.get() and CANCELLATION_REQUESTED != 0L ||
                    sink.scanUnsafe(Scannable.Attr.CANCELLED) == true
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
    private val registerActual: (CoreSubscriber<in T>) -> Unit,
    private val registerSubscription: (Subscription) -> Unit,
    private val requestCancellation: () -> Unit,
    private val isCancellationRequested: () -> Boolean,
    private val beginDownstreamOperation: () -> Unit,
    private val endDownstreamOperation: () -> Unit,
    private val scanAttribute: (Scannable.Attr<*>) -> Any?,
    private val innerScannables: () -> Stream<out Scannable>,
) : Flux<T>(),
    Scannable {
    private val firstSubscriber = AtomicBoolean()

    override fun subscribe(actual: CoreSubscriber<in T>) {
        beginDownstreamOperation()
        if (!firstSubscriber.compareAndSet(false, true)) {
            endDownstreamOperation()
            Operators.error(
                actual,
                IllegalStateException("Sinks.many().unicast() sinks only allow a single Subscriber"),
            )
            return
        }
        registerActual(actual)
        try {
            source.subscribe(
                OpaqueSubscriber(
                    actual = actual,
                    registerSubscription = registerSubscription,
                    requestCancellation = requestCancellation,
                    isCancellationRequested = isCancellationRequested,
                    beginDownstreamOperation = beginDownstreamOperation,
                    endDownstreamOperation = endDownstreamOperation,
                ),
            )
        } finally {
            endDownstreamOperation()
        }
    }

    override fun inners(): Stream<out Scannable> = innerScannables()

    override fun scanUnsafe(key: Scannable.Attr<*>): Any? =
        when (key) {
            /*
             * Preserve the opaque boundary around the factory-owned queue while
             * exposing the same public lifecycle and capacity attributes as the
             * owning Sinks.Many view.
             */
            Scannable.Attr.PARENT -> null
            else -> scanAttribute(key)
        }
}

private class OpaqueSubscriber<T : Any>(
    private val actual: CoreSubscriber<in T>,
    private val registerSubscription: (Subscription) -> Unit,
    private val requestCancellation: () -> Unit,
    private val isCancellationRequested: () -> Boolean,
    private val beginDownstreamOperation: () -> Unit,
    private val endDownstreamOperation: () -> Unit,
) : CoreSubscriber<T>, Scannable {
    override fun currentContext(): Context = actual.currentContext()

    override fun onSubscribe(subscription: Subscription) {
        registerSubscription(subscription)
        actual.onSubscribe(
            OpaqueSubscription(
                delegate = subscription,
                requestCancellation = requestCancellation,
                beginDownstreamOperation = beginDownstreamOperation,
                endDownstreamOperation = endDownstreamOperation,
            ),
        )
    }

    override fun onNext(value: T) {
        if (isCancellationRequested()) {
            Operators.onDiscard(value, currentContext())
            return
        }
        actual.onNext(value)
    }

    override fun onError(throwable: Throwable) {
        if (!isCancellationRequested()) {
            actual.onError(throwable)
        }
    }

    override fun onComplete() {
        if (!isCancellationRequested()) {
            actual.onComplete()
        }
    }

    override fun scanUnsafe(key: Scannable.Attr<*>): Any? =
        when (key) {
            Scannable.Attr.ACTUAL -> actual
            else -> null
        }
}

private class OpaqueSubscription(
    private val delegate: Subscription,
    private val requestCancellation: () -> Unit,
    private val beginDownstreamOperation: () -> Unit,
    private val endDownstreamOperation: () -> Unit,
) : Subscription {
    private val cancelled = AtomicBoolean()

    override fun request(elements: Long) {
        if (cancelled.get()) {
            return
        }
        beginDownstreamOperation()
        try {
            if (!cancelled.get()) {
                delegate.request(elements)
            }
        } finally {
            endDownstreamOperation()
        }
    }

    override fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            requestCancellation()
        }
    }
}
