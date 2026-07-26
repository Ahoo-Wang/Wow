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

package me.ahoo.wow.infra.batch

import me.ahoo.wow.infra.lifecycle.GracefullyStoppable
import reactor.core.Disposable
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates bounded, non-blocking admission and per-item completion for
 * storage-independent reactive batches.
 *
 * The coordinator owns batching, cancellation, backpressure and graceful
 * shutdown. A [ReactiveBatchWriter] owns protocol-specific request construction,
 * execution and result mapping.
 */
class ReactiveBatchCoordinator<T : Any>(
    val name: String,
    val options: ReactiveBatchOptions,
    private val writer: ReactiveBatchWriter<T>,
) : GracefullyStoppable {
    private sealed interface Lifecycle {
        data object Open : Lifecycle

        data object Closing : Lifecycle

        data object DrainingResults : Lifecycle

        data object Closed : Lifecycle

        data class Failed(val cause: Throwable) : Lifecycle
    }

    private sealed interface ItemState {
        data object Queued : ItemState

        data object InFlight : ItemState

        data object Cancelled : ItemState

        data class Settled(val outcome: BatchItemResult) : ItemState

        data object Terminated : ItemState
    }

    private inner class PendingItem(
        val value: T,
    ) {
        val result: Sinks.Empty<Void> = Sinks.empty()
        private val state = AtomicReference<ItemState>(ItemState.Queued)
        private val queueSlotHeld = AtomicBoolean(true)

        fun claim(): Boolean {
            while (true) {
                when (state.get()) {
                    ItemState.Queued -> {
                        if (state.compareAndSet(ItemState.Queued, ItemState.InFlight)) {
                            releaseQueueSlot()
                            return true
                        }
                    }

                    ItemState.Cancelled -> {
                        discardCancelled()
                        return false
                    }

                    ItemState.InFlight,
                    is ItemState.Settled,
                    ItemState.Terminated,
                    -> return false
                }
            }
        }

        fun cancel() {
            if (state.compareAndSet(ItemState.Queued, ItemState.Cancelled)) {
                releaseAdmission()
            }
        }

        fun discardAdmission() {
            if (state.compareAndSet(ItemState.Queued, ItemState.Terminated)) {
                releaseQueueSlot()
                releaseAdmission()
            }
        }

        fun settle(outcome: BatchItemResult) {
            while (true) {
                when (val current = state.get()) {
                    ItemState.InFlight -> {
                        if (state.compareAndSet(current, ItemState.Settled(outcome))) {
                            return
                        }
                    }

                    ItemState.Cancelled -> {
                        discardCancelled()
                        return
                    }

                    ItemState.Queued,
                    is ItemState.Settled,
                    ItemState.Terminated,
                    -> return
                }
            }
        }

        fun settleFailure(error: Throwable) = settle(BatchItemResult.Failure(error))

        fun signalSettled() {
            while (true) {
                when (val current = state.get()) {
                    is ItemState.Settled -> {
                        if (state.compareAndSet(current, ItemState.Terminated)) {
                            releaseAdmission()
                            when (val outcome = current.outcome) {
                                BatchItemResult.Success -> result.tryEmitEmpty()
                                is BatchItemResult.Failure -> result.tryEmitError(outcome.error)
                            }
                            return
                        }
                    }

                    ItemState.Queued,
                    ItemState.InFlight,
                    ItemState.Cancelled,
                    ItemState.Terminated,
                    -> return
                }
            }
        }

        fun failIfUnsettled(error: Throwable) {
            while (true) {
                when (val current = state.get()) {
                    ItemState.Queued,
                    ItemState.InFlight,
                    -> {
                        if (state.compareAndSet(current, ItemState.Terminated)) {
                            releaseQueueSlot()
                            releaseAdmission()
                            result.tryEmitError(error)
                            return
                        }
                    }

                    ItemState.Cancelled -> {
                        discardCancelled()
                        return
                    }

                    is ItemState.Settled,
                    ItemState.Terminated,
                    -> return
                }
            }
        }

        private fun discardCancelled() {
            if (state.compareAndSet(ItemState.Cancelled, ItemState.Terminated)) {
                releaseQueueSlot()
            }
        }

        private fun releaseAdmission() {
            if (pending.remove(this)) {
                availablePendingItems.release()
            }
        }

        private fun releaseQueueSlot() {
            if (queueSlotHeld.compareAndSet(true, false)) {
                availableQueuedItems.release()
            }
        }
    }

    private val emissionLock = Any()
    private val availablePendingItems = Semaphore(options.maxPendingItems)
    private val availableQueuedItems = Semaphore(options.maxPendingItems)
    private val itemQueue = ArrayBlockingQueue<PendingItem>(options.maxPendingItems)
    private val items = Sinks.many().unicast().onBackpressureBuffer(itemQueue)
    private val pending = ConcurrentHashMap.newKeySet<PendingItem>()
    private val lifecycle = AtomicReference<Lifecycle>(Lifecycle.Open)
    private val processorTermination = CompletableFuture<Unit>()
    private val termination = CompletableFuture<Unit>()
    private val resultDispatchContext = ThreadLocal<Boolean>()
    private val batchScheduler = Schedulers.newSingle("$name-batch-window", true)
    private val resultExecutor = object : ThreadPoolExecutor(
        RESULT_DISPATCHER_THREADS.coerceAtMost(options.maxPendingItems),
        RESULT_DISPATCHER_THREADS.coerceAtMost(options.maxPendingItems),
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(options.maxPendingItems),
        { runnable ->
            Thread(
                runnable,
                "$name-batch-result-${RESULT_THREAD_SEQUENCE.incrementAndGet()}"
            ).apply {
                isDaemon = true
            }
        }
    ) {
        override fun terminated() {
            completeResultDrain()
        }
    }
    private val processor: Disposable

    init {
        require(name.isNotBlank()) {
            "name must not be blank."
        }
        processor = items.asFlux()
            // Queue-slot admission bounds cancelled placeholders independently
            // from live-item admission while bufferTimeout owns a partial window.
            //
            // Keep source delivery and timeout flushes on the same thread. The
            // non-fair Reactor bufferTimeout implementation updates its timer
            // index before adding the item to the buffer; concurrent timeout
            // execution in that window can otherwise strand the final item.
            .publishOn(batchScheduler)
            .bufferTimeout(options.maxSize, options.maxDelay, batchScheduler)
            .onBackpressureBuffer(options.maxPendingItems)
            .concatMap(::writeBatch)
            .doFinally {
                batchScheduler.dispose()
            }
            .cancelOn(batchScheduler)
            .subscribe(
                {},
                ::terminateProcessor,
                ::completeProcessor,
            )
    }

    fun submit(item: T): Mono<Void> = submit { item }

    @Suppress("TooGenericExceptionCaught")
    fun submit(itemFactory: () -> T): Mono<Void> {
        return Mono.defer {
            terminalErrorOrClosed()?.let {
                return@defer Mono.error(it)
            }
            if (!availablePendingItems.tryAcquire()) {
                terminalErrorOrClosed()?.let {
                    return@defer Mono.error(it)
                }
                return@defer Mono.error(overflowError())
            }
            if (!availableQueuedItems.tryAcquire()) {
                availablePendingItems.release()
                terminalErrorOrClosed()?.let {
                    return@defer Mono.error(it)
                }
                return@defer Mono.error(overflowError())
            }
            terminalErrorOrClosed()?.let {
                availableQueuedItems.release()
                availablePendingItems.release()
                return@defer Mono.error(it)
            }
            val item = try {
                itemFactory()
            } catch (error: Throwable) {
                availableQueuedItems.release()
                availablePendingItems.release()
                Exceptions.throwIfFatal(error)
                return@defer Mono.error(error)
            }
            val pendingItem = PendingItem(item)
            pending.add(pendingItem)
            val emitResult = synchronized(emissionLock) {
                when (lifecycle.get()) {
                    Lifecycle.Open -> items.tryEmitNext(pendingItem)
                    Lifecycle.Closing,
                    Lifecycle.DrainingResults,
                    Lifecycle.Closed,
                    is Lifecycle.Failed,
                    -> Sinks.EmitResult.FAIL_TERMINATED
                }
            }
            if (emitResult.isFailure) {
                pendingItem.discardAdmission()
                return@defer Mono.error(enqueueFailure(emitResult))
            }
            pendingItem.result.asMono()
                .doOnCancel(pendingItem::cancel)
        }
    }

    private fun writeBatch(batch: List<PendingItem>): Mono<Void> {
        val claimedBatch = batch.filter { it.claim() }
        if (claimedBatch.isEmpty()) {
            return Mono.empty()
        }
        return Mono.defer {
            writer.write(claimedBatch.map { it.value })
        }.switchIfEmpty(
            Mono.error(
                ReactiveBatchProtocolException(
                    "Reactive batch writer[$name] completed without item results."
                )
            )
        ).flatMap { outcomes ->
            if (outcomes.size != claimedBatch.size) {
                return@flatMap Mono.error<Void>(
                    ReactiveBatchProtocolException(
                        "Reactive batch writer[$name] returned ${outcomes.size} item results " +
                            "for ${claimedBatch.size} inputs."
                    )
                )
            }
            claimedBatch.zip(outcomes).forEach { (item, outcome) ->
                item.settle(outcome)
            }
            dispatchResults {
                claimedBatch.forEach { it.signalSettled() }
            }
            Mono.empty()
        }.onErrorResume { error ->
            claimedBatch.forEach {
                it.settleFailure(error)
            }
            dispatchResults {
                claimedBatch.forEach { it.signalSettled() }
            }
            Mono.empty()
        }
    }

    private fun dispatchResults(signal: () -> Unit) {
        val contextualSignal = Runnable {
            val previousContext = resultDispatchContext.get()
            resultDispatchContext.set(true)
            try {
                signal()
            } finally {
                if (previousContext == null) {
                    resultDispatchContext.remove()
                } else {
                    resultDispatchContext.set(previousContext)
                }
            }
        }
        try {
            resultExecutor.execute(contextualSignal)
        } catch (_: RejectedExecutionException) {
            contextualSignal.run()
        }
    }

    private fun failPending(error: Throwable) {
        pending.toList().forEach {
            it.failIfUnsettled(error)
        }
    }

    private fun overflowError(): ReactiveBatchOverflowException =
        ReactiveBatchOverflowException(name, options.maxPendingItems)

    private fun closedError(): ReactiveBatchClosedException =
        ReactiveBatchClosedException(name)

    private fun terminalErrorOrClosed(): Throwable? {
        return when (val current = lifecycle.get()) {
            Lifecycle.Open -> null
            Lifecycle.Closing,
            Lifecycle.DrainingResults,
            Lifecycle.Closed,
            -> closedError()

            is Lifecycle.Failed -> current.cause
        }
    }

    private fun enqueueFailure(emitResult: Sinks.EmitResult): Throwable {
        when (val current = lifecycle.get()) {
            is Lifecycle.Failed -> return current.cause
            Lifecycle.Open,
            Lifecycle.Closing,
            Lifecycle.DrainingResults,
            Lifecycle.Closed,
            -> Unit
        }
        return when (emitResult) {
            Sinks.EmitResult.FAIL_OVERFLOW -> overflowError()
            Sinks.EmitResult.FAIL_CANCELLED,
            Sinks.EmitResult.FAIL_TERMINATED,
            -> closedError()

            else -> IllegalStateException(
                "Failed to enqueue reactive batch item[$name]: $emitResult"
            )
        }
    }

    private fun terminateProcessor(error: Throwable) {
        failLifecycle(error, disposeProcessor = false)
    }

    private fun completeProcessor() {
        val terminalLifecycle = synchronized(emissionLock) {
            var terminal: Lifecycle? = null
            while (terminal == null) {
                when (val current = lifecycle.get()) {
                    Lifecycle.Open,
                    Lifecycle.Closing,
                    -> {
                        if (lifecycle.compareAndSet(current, Lifecycle.DrainingResults)) {
                            terminal = Lifecycle.DrainingResults
                        }
                    }

                    Lifecycle.DrainingResults -> terminal = Lifecycle.DrainingResults
                    Lifecycle.Closed -> terminal = Lifecycle.Closed
                    is Lifecycle.Failed -> terminal = current
                }
            }
            checkNotNull(terminal)
        }
        when (terminalLifecycle) {
            Lifecycle.Open,
            Lifecycle.Closing,
            -> error("Unexpected reactive batch lifecycle[$name]: $terminalLifecycle")

            Lifecycle.DrainingResults -> processorTermination.complete(Unit)
            Lifecycle.Closed -> {
                processorTermination.complete(Unit)
                termination.complete(Unit)
            }

            is Lifecycle.Failed -> {
                processorTermination.completeExceptionally(terminalLifecycle.cause)
                termination.completeExceptionally(terminalLifecycle.cause)
            }
        }
        resultExecutor.shutdown()
    }

    private fun completeResultDrain() {
        val terminalLifecycle = synchronized(emissionLock) {
            var terminal: Lifecycle? = null
            while (terminal == null) {
                when (val current = lifecycle.get()) {
                    Lifecycle.DrainingResults -> {
                        if (lifecycle.compareAndSet(current, Lifecycle.Closed)) {
                            terminal = Lifecycle.Closed
                        }
                    }

                    Lifecycle.Closed -> terminal = Lifecycle.Closed
                    is Lifecycle.Failed -> terminal = current
                    Lifecycle.Open,
                    Lifecycle.Closing,
                    -> {
                        val failed = Lifecycle.Failed(
                            IllegalStateException(
                                "Reactive batch result dispatcher[$name] terminated before the processor."
                            )
                        )
                        if (lifecycle.compareAndSet(current, failed)) {
                            terminal = failed
                        }
                    }
                }
            }
            checkNotNull(terminal)
        }
        when (terminalLifecycle) {
            Lifecycle.Open,
            Lifecycle.Closing,
            Lifecycle.DrainingResults,
            -> error("Unexpected reactive batch lifecycle[$name]: $terminalLifecycle")

            Lifecycle.Closed -> termination.complete(Unit)
            is Lifecycle.Failed -> {
                processorTermination.completeExceptionally(terminalLifecycle.cause)
                termination.completeExceptionally(terminalLifecycle.cause)
                failPending(terminalLifecycle.cause)
            }
        }
    }

    private fun initiateClose() {
        synchronized(emissionLock) {
            while (true) {
                when (val current = lifecycle.get()) {
                    Lifecycle.Open -> {
                        if (lifecycle.compareAndSet(current, Lifecycle.Closing)) {
                            break
                        }
                    }

                    Lifecycle.Closing,
                    Lifecycle.DrainingResults,
                    Lifecycle.Closed,
                    is Lifecycle.Failed,
                    -> return
                }
            }
        }
        val emitResult = items.tryEmitComplete()
        if (emitResult.isFailure && emitResult != Sinks.EmitResult.FAIL_TERMINATED) {
            failLifecycle(
                IllegalStateException(
                    "Failed to close reactive batch coordinator[$name]: $emitResult"
                ),
                disposeProcessor = true,
            )
        }
    }

    override fun stopGracefully(): Mono<Void> {
        initiateClose()
        return Mono.fromFuture(termination).then()
    }

    override fun close() {
        close(DEFAULT_CLOSE_TIMEOUT)
    }

    fun close(timeout: Duration) {
        require(!timeout.isNegative && !timeout.isZero) {
            "timeout must be positive."
        }
        initiateClose()
        val closeTermination = if (
            resultDispatchContext.get() == true &&
            lifecycle.get() !is Lifecycle.Failed
        ) {
            processorTermination
        } else {
            termination
        }
        try {
            closeTermination.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (error: TimeoutException) {
            closeTimedOut(timeout)?.let {
                throw it
            }
        } catch (error: InterruptedException) {
            closeInterrupted(error)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun closeTimedOut(timeout: Duration): Throwable? {
        return failLifecycle(
            ReactiveBatchCloseTimeoutException(name, timeout),
            disposeProcessor = true,
        )
    }

    private fun closeInterrupted(error: InterruptedException): Nothing {
        Thread.currentThread().interrupt()
        val interruption = IllegalStateException(
            "Interrupted while closing reactive batch coordinator[$name].",
            error,
        )
        throw failLifecycle(interruption, disposeProcessor = true) ?: interruption
    }

    private fun failLifecycle(error: Throwable, disposeProcessor: Boolean): Throwable? {
        val terminalLifecycle = synchronized(emissionLock) {
            var terminal: Lifecycle? = null
            while (terminal == null) {
                when (val current = lifecycle.get()) {
                    Lifecycle.Open,
                    Lifecycle.Closing,
                    Lifecycle.DrainingResults,
                    -> {
                        val failed = Lifecycle.Failed(error)
                        if (lifecycle.compareAndSet(current, failed)) {
                            terminal = failed
                        }
                    }

                    Lifecycle.Closed -> terminal = Lifecycle.Closed
                    is Lifecycle.Failed -> terminal = current
                }
            }
            checkNotNull(terminal)
        }
        if (terminalLifecycle == Lifecycle.Closed) {
            termination.complete(Unit)
            return null
        }
        val terminalError = when (terminalLifecycle) {
            Lifecycle.Open,
            Lifecycle.Closing,
            Lifecycle.DrainingResults,
            Lifecycle.Closed,
            -> error("Unexpected reactive batch lifecycle[$name]: $terminalLifecycle")

            is Lifecycle.Failed -> terminalLifecycle.cause
        }
        if (disposeProcessor) {
            processor.dispose()
        }
        resultExecutor.shutdown()
        processorTermination.completeExceptionally(terminalError)
        termination.completeExceptionally(terminalError)
        failPending(terminalError)
        return terminalError
    }

    private companion object {
        val DEFAULT_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(30)
        const val RESULT_DISPATCHER_THREADS: Int = 4
        val RESULT_THREAD_SEQUENCE: AtomicInteger = AtomicInteger()
    }
}
