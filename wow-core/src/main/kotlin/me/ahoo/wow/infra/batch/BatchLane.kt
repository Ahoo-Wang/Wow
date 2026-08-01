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

import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler

/**
 * A serial batch pipeline. Different lanes may write concurrently, while
 * `concatMap` preserves batch order inside this lane.
 *
 * The sink uses an unbounded implementation, but the shared [BatchAdmission]
 * bounds the total number of queued requests across all lanes.
 */
internal class BatchLane<T : Any>(
    private val name: String,
    private val lane: Int,
    options: BatchOptions,
    private val writer: BatchWriter<T>,
    scheduler: Scheduler,
    private val resultDispatcher: BatchResultDispatcher,
    private val observations: BatchObservationEmitter?,
    onError: (Throwable) -> Unit,
    onComplete: () -> Unit,
) {
    private val maxSize = options.maxSize
    private val requests = Sinks.many()
        .unicast()
        .onBackpressureBuffer<BatchRequest<T>>()
    private val processor: Disposable = requests.asFlux()
        // Source delivery and timeout flushes must share one thread. Reactor's
        // non-fair bufferTimeout can otherwise strand the final item when a
        // concurrent timeout observes the timer index before the buffer update.
        .publishOn(scheduler)
        .bufferTimeout(options.maxSize, options.maxDelay, scheduler)
        .onBackpressureBuffer(options.maxPendingItems)
        .concatMap(::writeBatch)
        .cancelOn(scheduler)
        .subscribe(
            {},
            onError,
            onComplete,
        )

    fun emit(request: BatchRequest<T>): Sinks.EmitResult =
        requests.tryEmitNext(request)

    fun complete(): Sinks.EmitResult = requests.tryEmitComplete()

    fun dispose() {
        processor.dispose()
    }

    private fun writeBatch(batch: List<BatchRequest<T>>): Mono<Void> {
        val claimedBatch = batch.filter { it.claim(lane) }
        if (claimedBatch.isEmpty()) {
            return Mono.empty()
        }
        val currentObservations = observations
            ?: return writeUnobservedBatch(claimedBatch)
        val batchWrite = currentObservations.batchWriteStarted(
            lane = lane,
            bufferedItems = batch.size,
            writtenItems = claimedBatch.size,
            windowType = if (batch.size == maxSize) {
                BatchWindowType.FULL
            } else {
                BatchWindowType.PARTIAL
            },
        )
        return writeObservedBatch(claimedBatch, batchWrite)
    }

    private fun writeUnobservedBatch(
        claimedBatch: List<BatchRequest<T>>,
    ): Mono<Void> {
        return Mono.defer {
            writer.write(claimedBatch.map { it.value })
        }.switchIfEmpty(
            Mono.error(
                BatchProtocolException(
                    "Batch writer[$name] completed without item results."
                )
            )
        ).flatMap { outcomes ->
            if (outcomes.size != claimedBatch.size) {
                return@flatMap Mono.error<Void>(
                    BatchProtocolException(
                        "Batch writer[$name] returned ${outcomes.size} item results " +
                            "for ${claimedBatch.size} inputs."
                    )
                )
            }
            claimedBatch.zip(outcomes).forEach { (item, outcome) ->
                item.settle(outcome)
            }
            dispatchResults(claimedBatch)
            Mono.empty()
        }.onErrorResume { error ->
            claimedBatch.forEach { it.settleFailure(error) }
            dispatchResults(claimedBatch)
            Mono.empty()
        }
    }

    private fun writeObservedBatch(
        claimedBatch: List<BatchRequest<T>>,
        batchWrite: BatchWriteObservation,
    ): Mono<Void> {
        return Mono.defer {
            writer.write(claimedBatch.map { it.value })
        }.switchIfEmpty(
            Mono.error(
                BatchProtocolException(
                    "Batch writer[$name] completed without item results."
                )
            )
        ).flatMap { outcomes ->
            completeBatch(claimedBatch, outcomes, batchWrite)
        }.onErrorResume { error ->
            failBatch(claimedBatch, error, batchWrite)
        }.doOnCancel {
            batchWrite.complete(
                outcome = BatchWriteOutcome.CANCELLED,
                failedItems = claimedBatch.size,
                failure = null,
            )
        }
    }

    private fun completeBatch(
        claimedBatch: List<BatchRequest<T>>,
        outcomes: List<BatchItemResult>,
        batchWrite: BatchWriteObservation,
    ): Mono<Void> {
        if (outcomes.size != claimedBatch.size) {
            return Mono.error(
                BatchProtocolException(
                    "Batch writer[$name] returned ${outcomes.size} item results " +
                        "for ${claimedBatch.size} inputs."
                )
            )
        }
        val failedItems = outcomes.count { it is BatchItemResult.Failure }
        batchWrite.complete(
            outcome = if (failedItems == 0) {
                BatchWriteOutcome.SUCCESS
            } else {
                BatchWriteOutcome.ITEM_FAILURE
            },
            failedItems = failedItems,
            failure = null,
        )
        claimedBatch.zip(outcomes).forEach { (item, outcome) ->
            item.settle(outcome)
        }
        dispatchResults(claimedBatch)
        return Mono.empty()
    }

    private fun failBatch(
        claimedBatch: List<BatchRequest<T>>,
        error: Throwable,
        batchWrite: BatchWriteObservation,
    ): Mono<Void> {
        batchWrite.complete(
            outcome = BatchWriteOutcome.FAILED,
            failedItems = claimedBatch.size,
            failure = error,
        )
        claimedBatch.forEach { it.settleFailure(error) }
        dispatchResults(claimedBatch)
        return Mono.empty()
    }

    private fun dispatchResults(batch: List<BatchRequest<T>>) {
        batch.forEach { item ->
            resultDispatcher.dispatch(item::signalSettled)
        }
    }
}
