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

package me.ahoo.wow.mongo

import com.mongodb.MongoBulkWriteException
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.mongo.AggregateSchemaInitializer.toEventStreamCollectionName
import org.bson.Document
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.kotlin.core.publisher.toMono
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class MongoEventStoreBatcher(
    private val database: MongoDatabase,
    private val options: MongoEventStoreBatchOptions,
    private val closeTimeout: Duration = DEFAULT_CLOSE_TIMEOUT,
) : AutoCloseable {
    private sealed interface Lifecycle {
        data object Open : Lifecycle

        data object Closing : Lifecycle

        data object Closed : Lifecycle

        data class Failed(val cause: Throwable) : Lifecycle
    }

    private sealed interface AppendOutcome {
        data object Success : AppendOutcome

        data class Failure(val error: Throwable) : AppendOutcome
    }

    private sealed interface RequestState {
        data object Queued : RequestState

        data object InFlight : RequestState

        data object Cancelled : RequestState

        data class Settled(val outcome: AppendOutcome) : RequestState

        data object Terminated : RequestState
    }

    private inner class AppendRequest(
        val eventStream: DomainEventStream,
        val document: Document,
    ) {
        val collectionName: String = eventStream.toEventStreamCollectionName()
        val result: Sinks.Empty<Void> = Sinks.empty()
        private val state = AtomicReference<RequestState>(RequestState.Queued)

        fun claim(): Boolean {
            while (true) {
                when (state.get()) {
                    RequestState.Queued -> {
                        if (state.compareAndSet(RequestState.Queued, RequestState.InFlight)) {
                            return true
                        }
                    }

                    RequestState.Cancelled -> {
                        discardCancelled()
                        return false
                    }

                    RequestState.InFlight,
                    is RequestState.Settled,
                    RequestState.Terminated,
                    -> return false
                }
            }
        }

        fun cancel() {
            state.compareAndSet(RequestState.Queued, RequestState.Cancelled)
        }

        fun discardAdmission() {
            if (state.compareAndSet(RequestState.Queued, RequestState.Terminated)) {
                release()
            }
        }

        fun settleSuccess() = settle(AppendOutcome.Success)

        fun settleFailure(error: Throwable) = settle(AppendOutcome.Failure(error))

        private fun discardCancelled() {
            if (state.compareAndSet(RequestState.Cancelled, RequestState.Terminated)) {
                release()
            }
        }

        private fun settle(outcome: AppendOutcome) {
            while (true) {
                when (val current = state.get()) {
                    RequestState.InFlight -> {
                        if (state.compareAndSet(current, RequestState.Settled(outcome))) {
                            return
                        }
                    }

                    RequestState.Cancelled -> {
                        discardCancelled()
                        return
                    }

                    RequestState.Queued,
                    is RequestState.Settled,
                    RequestState.Terminated,
                    -> return
                }
            }
        }

        fun signalSettled() {
            while (true) {
                when (val current = state.get()) {
                    is RequestState.Settled -> {
                        if (state.compareAndSet(current, RequestState.Terminated)) {
                            release()
                            when (val outcome = current.outcome) {
                                AppendOutcome.Success -> result.tryEmitEmpty()
                                is AppendOutcome.Failure -> result.tryEmitError(outcome.error)
                            }
                            return
                        }
                    }

                    RequestState.Queued,
                    RequestState.InFlight,
                    RequestState.Cancelled,
                    RequestState.Terminated,
                    -> return
                }
            }
        }

        fun failIfUnsettled(error: Throwable) {
            while (true) {
                when (val current = state.get()) {
                    RequestState.Queued,
                    RequestState.InFlight,
                    -> {
                        if (state.compareAndSet(current, RequestState.Terminated)) {
                            release()
                            result.tryEmitError(error)
                            return
                        }
                    }

                    RequestState.Cancelled -> {
                        discardCancelled()
                        return
                    }

                    is RequestState.Settled,
                    RequestState.Terminated,
                    -> return
                }
            }
        }

        private fun release() {
            pending.remove(this)
            availablePendingAppends.release()
        }
    }

    private val emissionLock = Any()
    private val availablePendingAppends = Semaphore(options.maxPendingAppends)
    private val requestQueue = ArrayBlockingQueue<AppendRequest>(options.maxPendingAppends)
    private val requests = Sinks.many().unicast().onBackpressureBuffer(requestQueue)
    private val pending = ConcurrentHashMap.newKeySet<AppendRequest>()
    private val lifecycle = AtomicReference<Lifecycle>(Lifecycle.Open)
    private val termination = CompletableFuture<Unit>()
    private val resultExecutor = ThreadPoolExecutor(
        RESULT_DISPATCHER_THREADS.coerceAtMost(options.maxPendingAppends),
        RESULT_DISPATCHER_THREADS.coerceAtMost(options.maxPendingAppends),
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(options.maxPendingAppends),
        { runnable ->
            Thread(
                runnable,
                "wow-mongo-event-store-result-${RESULT_THREAD_SEQUENCE.incrementAndGet()}"
            ).apply {
                isDaemon = true
            }
        }
    )
    private val processor: Disposable

    init {
        require(!closeTimeout.isNegative && !closeTimeout.isZero) {
            "closeTimeout must be positive."
        }
        processor = requests.asFlux()
            // The fair-backpressure variant can strand the remainder of a partial
            // window in a timeout/upstream race. The intermediate queue requests
            // every buffer eagerly but remains bounded by append admission.
            .bufferTimeout(options.maxSize, options.maxDelay)
            .onBackpressureBuffer(options.maxPendingAppends)
            .concatMap(::appendBatch)
            .subscribe(
                {},
                ::terminateProcessor,
                ::completeProcessor,
            )
    }

    fun append(eventStream: DomainEventStream): Mono<Void> {
        return Mono.defer {
            terminalErrorOrClosed()?.let {
                return@defer Mono.error(it)
            }
            val document = eventStream.toDocument()
            val request = AppendRequest(eventStream, document)
            if (!availablePendingAppends.tryAcquire()) {
                terminalErrorOrClosed()?.let {
                    return@defer Mono.error(it)
                }
                return@defer Mono.error(
                    MongoEventStoreBatchOverflowException(options.maxPendingAppends)
                )
            }
            pending.add(request)
            val emitResult = synchronized(emissionLock) {
                when (lifecycle.get()) {
                    Lifecycle.Open -> requests.tryEmitNext(request)
                    Lifecycle.Closing,
                    Lifecycle.Closed,
                    is Lifecycle.Failed,
                    -> Sinks.EmitResult.FAIL_TERMINATED
                }
            }
            if (emitResult.isFailure) {
                request.discardAdmission()
                return@defer Mono.error(enqueueFailure(emitResult))
            }
            request.result.asMono()
                .doOnCancel(request::cancel)
        }
    }

    private fun appendBatch(batch: List<AppendRequest>): Mono<Void> {
        val claimedBatch = batch.filter(AppendRequest::claim)
        if (claimedBatch.isEmpty()) {
            return Mono.empty()
        }
        return Flux.fromIterable(claimedBatch.groupBy(AppendRequest::collectionName).values)
            .flatMap(::appendCollectionBatch)
            .then()
    }

    private fun appendCollectionBatch(batch: List<AppendRequest>): Mono<Void> {
        return Mono.defer {
            database.getCollection(batch.first().collectionName)
                .insertMany(batch.map(AppendRequest::document), UNORDERED_INSERT_MANY_OPTIONS)
                .toMono()
        }.doOnNext {
            check(it.wasAcknowledged()) {
                "MongoDB did not acknowledge the event stream batch append."
            }
        }.then()
            .doOnSuccess {
                batch.forEach(AppendRequest::settleSuccess)
                dispatchResults {
                    batch.forEach(AppendRequest::signalSettled)
                }
            }.onErrorResume(MongoBulkWriteException::class.java) { error ->
                settleBulkWriteError(batch, error)
                dispatchResults { batch.forEach(AppendRequest::signalSettled) }
                Mono.empty()
            }.onErrorResume { error ->
                batch.forEach {
                    it.settleFailure(error)
                }
                dispatchResults { batch.forEach(AppendRequest::signalSettled) }
                Mono.empty()
            }
    }

    private fun settleBulkWriteError(batch: List<AppendRequest>, error: MongoBulkWriteException) {
        val writeErrors = error.writeErrors
        val errorsByIndex = writeErrors.associateBy { it.index }
        val writeConcernError = error.writeConcernError?.toWowError(error)
        val invalidWriteErrors = errorsByIndex.size != writeErrors.size ||
            errorsByIndex.keys.any { it !in batch.indices }
        val invalidWriteResult = writeConcernError == null &&
            (
                writeErrors.isEmpty() ||
                    !error.writeResult.wasAcknowledged() ||
                    error.writeResult.insertedCount != batch.size - writeErrors.size
                )
        if (invalidWriteErrors || invalidWriteResult) {
            batch.forEach {
                it.settleFailure(error)
            }
            return
        }
        batch.forEachIndexed { index, request ->
            val writeError = errorsByIndex[index]
            when {
                writeError != null -> request.settleFailure(writeError.toWowError(request.eventStream, error))
                writeConcernError != null -> request.settleFailure(writeConcernError)
                else -> request.settleSuccess()
            }
        }
    }

    private fun dispatchResults(signal: () -> Unit) {
        try {
            resultExecutor.execute(signal)
        } catch (_: RejectedExecutionException) {
            signal()
        }
    }

    private fun failPending(error: Throwable) {
        pending.toList().forEach {
            it.failIfUnsettled(error)
        }
    }

    private fun terminalErrorOrClosed(): Throwable? {
        return when (val current = lifecycle.get()) {
            Lifecycle.Open -> null
            Lifecycle.Closing,
            Lifecycle.Closed,
            -> IllegalStateException("MongoEventStore is closed.")

            is Lifecycle.Failed -> current.cause
        }
    }

    private fun enqueueFailure(emitResult: Sinks.EmitResult): Throwable {
        when (val current = lifecycle.get()) {
            is Lifecycle.Failed -> return current.cause
            Lifecycle.Open,
            Lifecycle.Closing,
            Lifecycle.Closed,
            -> Unit
        }
        return when (emitResult) {
            Sinks.EmitResult.FAIL_OVERFLOW ->
                MongoEventStoreBatchOverflowException(options.maxPendingAppends)

            Sinks.EmitResult.FAIL_CANCELLED,
            Sinks.EmitResult.FAIL_TERMINATED,
            -> IllegalStateException("MongoEventStore is closed.")

            else -> IllegalStateException("Failed to enqueue MongoEventStore append: $emitResult")
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
                        if (lifecycle.compareAndSet(current, Lifecycle.Closed)) {
                            terminal = Lifecycle.Closed
                        }
                    }

                    Lifecycle.Closed -> terminal = Lifecycle.Closed

                    is Lifecycle.Failed -> terminal = current
                }
            }
            checkNotNull(terminal)
        }
        resultExecutor.shutdown()
        when (terminalLifecycle) {
            Lifecycle.Open,
            Lifecycle.Closing,
            -> error("Unexpected MongoEventStore lifecycle: $terminalLifecycle")

            Lifecycle.Closed -> termination.complete(Unit)
            is Lifecycle.Failed -> termination.completeExceptionally(terminalLifecycle.cause)
        }
    }

    private fun initiateClose() {
        var emitResult: Sinks.EmitResult? = null
        synchronized(emissionLock) {
            while (true) {
                when (val current = lifecycle.get()) {
                    Lifecycle.Open -> {
                        if (lifecycle.compareAndSet(current, Lifecycle.Closing)) {
                            emitResult = requests.tryEmitComplete()
                            break
                        }
                    }

                    Lifecycle.Closing,
                    Lifecycle.Closed,
                    is Lifecycle.Failed,
                    -> return
                }
            }
        }
        if (emitResult?.isFailure == true && emitResult != Sinks.EmitResult.FAIL_TERMINATED) {
            val error = IllegalStateException("Failed to close MongoEventStore batcher: $emitResult")
            failLifecycle(error, disposeProcessor = true)
        }
    }

    override fun close() {
        initiateClose()
        awaitTermination()
    }

    private fun awaitTermination() {
        try {
            termination.get(closeTimeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (error: TimeoutException) {
            closeTimedOut()?.let {
                throw it
            }
        } catch (error: InterruptedException) {
            closeInterrupted(error)
        } catch (error: ExecutionException) {
            rethrowProcessorFailure(error)
        }
    }

    private fun closeTimedOut(): Throwable? {
        return failLifecycle(
            MongoEventStoreBatchCloseTimeoutException(closeTimeout),
            disposeProcessor = true,
        )
    }

    private fun closeInterrupted(error: InterruptedException): Nothing {
        Thread.currentThread().interrupt()
        val interruption = IllegalStateException("Interrupted while closing MongoEventStore batcher.", error)
        throw failLifecycle(interruption, disposeProcessor = true) ?: interruption
    }

    private fun rethrowProcessorFailure(error: ExecutionException): Nothing {
        throw error.cause ?: error
    }

    private fun failLifecycle(error: Throwable, disposeProcessor: Boolean): Throwable? {
        val terminalLifecycle = synchronized(emissionLock) {
            var terminal: Lifecycle? = null
            while (terminal == null) {
                when (val current = lifecycle.get()) {
                    Lifecycle.Open,
                    Lifecycle.Closing,
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
            Lifecycle.Closed,
            -> error("Unexpected MongoEventStore lifecycle: $terminalLifecycle")

            is Lifecycle.Failed -> terminalLifecycle.cause
        }
        if (disposeProcessor) {
            processor.dispose()
        }
        resultExecutor.shutdown()
        termination.completeExceptionally(terminalError)
        failPending(terminalError)
        return terminalError
    }

    private companion object {
        val UNORDERED_INSERT_MANY_OPTIONS: InsertManyOptions = InsertManyOptions().ordered(false)
        val DEFAULT_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(30)
        const val RESULT_DISPATCHER_THREADS: Int = 4
        val RESULT_THREAD_SEQUENCE = AtomicInteger()
    }
}
