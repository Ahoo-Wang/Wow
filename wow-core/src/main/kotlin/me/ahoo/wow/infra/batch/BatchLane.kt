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
    private val settle: (List<BatchRequest<T>>, List<BatchItemResult>) -> Unit,
    private val metrics: BatchMetrics?,
    onError: (Throwable) -> Unit,
    onComplete: () -> Unit,
) {
    private val maxSize = options.maxSize
    private val requests = Sinks.many()
        .unicast()
        .onBackpressureBuffer<BatchRequest<T>>()
    private val processor: Disposable = requests.asFlux()
        // Keep source delivery scheduled. Fair buffering retains individual items
        // while the writer is busy and assembles the next batch when demand resumes.
        .publishOn(scheduler)
        .bufferTimeout(options.maxSize, options.maxDelay, scheduler, true)
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
        val batchWrite = metrics?.batchWriteStarted(
            lane = lane,
            bufferedItems = batch.size,
            writtenItems = claimedBatch.size,
            windowType = if (batch.size == maxSize) {
                BatchWindowType.FULL
            } else {
                BatchWindowType.PARTIAL
            },
        )
        return Mono.defer {
            writer.write(claimedBatch.map { it.value })
        }.switchIfEmpty(
            Mono.error {
                BatchProtocolException(
                    "Batch writer[$name] completed without item results."
                )
            }
        ).flatMap { outcomes ->
            completeBatch(claimedBatch, outcomes, batchWrite)
        }.onErrorResume { error ->
            failBatch(claimedBatch, error, batchWrite)
        }.doOnCancel {
            batchWrite?.complete(
                outcome = BatchWriteOutcome.CANCELLED,
                failedItems = claimedBatch.size,
            )
        }
    }

    private fun completeBatch(
        claimedBatch: List<BatchRequest<T>>,
        outcomes: List<BatchItemResult>,
        batchWrite: BatchWriteMetrics?,
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
        batchWrite?.complete(
            outcome = if (failedItems == 0) {
                BatchWriteOutcome.SUCCESS
            } else {
                BatchWriteOutcome.ITEM_FAILURE
            },
            failedItems = failedItems,
        )
        settle(claimedBatch, outcomes)
        return Mono.empty()
    }

    private fun failBatch(
        claimedBatch: List<BatchRequest<T>>,
        error: Throwable,
        batchWrite: BatchWriteMetrics?,
    ): Mono<Void> {
        batchWrite?.complete(
            outcome = BatchWriteOutcome.FAILED,
            failedItems = claimedBatch.size,
        )
        settle(claimedBatch, List(claimedBatch.size) { BatchItemResult.Failure(error) })
        return Mono.empty()
    }
}
