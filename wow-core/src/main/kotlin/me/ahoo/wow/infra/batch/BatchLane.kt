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
import java.util.concurrent.atomic.AtomicReference

/**
 * A serial batch pipeline. Different lanes may write concurrently, while
 * `concatMap` preserves batch order inside this lane.
 *
 * The sink uses an unbounded implementation, but the shared [BatchAdmission]
 * bounds the total number of queued requests across all lanes.
 */
internal class BatchLane<T : Any>(
    private val name: String,
    options: BatchOptions,
    private val writer: BatchWriter<T>,
    scheduler: Scheduler,
    settleResults: (List<BatchRequest<T>>, List<BatchItemResult>) -> Unit,
    onError: (Throwable) -> Unit,
    onComplete: () -> Unit,
) {
    private data class TerminalCallbacks(
        val onError: (Throwable) -> Unit,
        val onComplete: () -> Unit,
    )

    private val requests = Sinks.many()
        .unicast()
        .onBackpressureBuffer<BatchRequest<T>>()
    private val terminalCallbacks = AtomicReference<TerminalCallbacks?>(
        TerminalCallbacks(
            onError = onError,
            onComplete = onComplete,
        ),
    )
    private val processor = AtomicReference<Disposable?>()
    private val resultSettler =
        AtomicReference<((List<BatchRequest<T>>, List<BatchItemResult>) -> Unit)?>(settleResults)

    internal val isResultCallbackDetached: Boolean
        get() = resultSettler.get() == null

    init {
        processor.set(
            requests.asFlux()
                // Source delivery and timeout flushes must share one thread. Reactor's
                // non-fair bufferTimeout can otherwise strand the final item when a
                // concurrent timeout observes the timer index before the buffer update.
                .publishOn(scheduler)
                .bufferTimeout(options.maxSize, options.maxDelay, scheduler)
                .onBackpressureBuffer(options.maxPendingItems)
                .concatMap(::writeBatch)
                .subscribe(
                    {},
                    { error ->
                        terminalCallbacks.get()?.onError?.invoke(error)
                    },
                    {
                        terminalCallbacks.get()?.onComplete?.invoke()
                    },
                ),
        )
    }

    fun emit(request: BatchRequest<T>): Sinks.EmitResult =
        requests.tryEmitNext(request)

    fun complete(): Sinks.EmitResult = requests.tryEmitComplete()

    fun detachCallbacks(detachResultDispatcher: Boolean) {
        terminalCallbacks.set(null)
        if (detachResultDispatcher) {
            resultSettler.set(null)
        }
    }

    /**
     * Transfers the processor handle without invoking publisher or user code.
     * The returned handle may be disposed later on a bounded cleanup worker.
     */
    fun detachProcessor(): Disposable? = processor.getAndSet(null)

    private fun writeBatch(batch: List<BatchRequest<T>>): Mono<Void> {
        val claimedBatch = batch.filter { it.claim() }
        if (claimedBatch.isEmpty()) {
            return Mono.empty()
        }
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
            resultSettler.get()?.invoke(claimedBatch, outcomes)
            Mono.empty()
        }.onErrorResume { error ->
            resultSettler.get()?.invoke(
                claimedBatch,
                List(claimedBatch.size) {
                    BatchItemResult.Failure(error)
                },
            )
            Mono.empty()
        }
    }
}
