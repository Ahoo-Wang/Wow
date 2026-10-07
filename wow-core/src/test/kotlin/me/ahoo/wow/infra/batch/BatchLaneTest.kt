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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BatchLaneTest {
    @Test
    fun `resuming writer demand should immediately flush accumulated partial batch`() {
        LaneFixture().use { fixture ->
            fixture.submit(1)
            fixture.scheduler.advanceTimeBy(Duration.ofMillis(1))
            fixture.submit(2)
            fixture.scheduler.advanceTimeBy(Duration.ofNanos(500_000))
            fixture.batches.assert().containsExactly(listOf(1))

            fixture.releaseWriter()

            fixture.batches.assert().containsExactly(listOf(1), listOf(2))
            fixture.lane.complete()
            fixture.scheduler.advanceTimeBy(Duration.ZERO)
            fixture.assertDrained(listOf(1, 2))
        }
    }

    @Test
    fun `writer demand should combine items accumulated across timeout windows`() {
        LaneFixture().use { fixture ->
            (1..13).forEach {
                fixture.submit(it)
                fixture.scheduler.advanceTimeBy(Duration.ofMillis(2))
            }
            fixture.batches.assert().containsExactly(listOf(1))
            fixture.lane.complete()
            fixture.scheduler.advanceTimeBy(Duration.ZERO)
            fixture.completed.assert().isFalse()

            fixture.releaseWriter()

            fixture.batches.map { it.size }.assert().containsExactly(1, 4, 4, 4)
            fixture.assertDrained((1..13).toList())
        }
    }

    @Test
    fun `close should drain ordered backlog larger than fair prefetch including final partial batch`() {
        LaneFixture().use { fixture ->
            fixture.submit(1)
            fixture.scheduler.advanceTimeBy(Duration.ofMillis(2))
            // maxSize=4 gives fair prefetch=16. Forty-two queued items exceed that demand.
            (2..43).forEach(fixture::submit)
            fixture.lane.complete()
            fixture.scheduler.advanceTimeBy(Duration.ofSeconds(1))
            fixture.batches.assert().containsExactly(listOf(1))
            fixture.completed.assert().isFalse()

            fixture.releaseWriter()
            fixture.scheduler.advanceTimeBy(Duration.ZERO)

            fixture.assertDrained((1..43).toList())
            (fixture.batches.last().size in 1..<4).assert().isTrue()
            fixture.batches.all { it.size <= 4 }.assert().isTrue()
        }
    }

    @Test
    fun `queued cancellation should retain physical capacity until writer consumes placeholders`() {
        LaneFixture(maxPendingItems = 8).use { fixture ->
            fixture.submit(1)
            fixture.scheduler.advanceTimeBy(Duration.ofMillis(2))
            (2..8).forEach { fixture.submit(it).cancel() }
            // In-flight item 1 already released its physical slot, leaving exactly one slot.
            fixture.submit(9).cancel()
            repeat(100) {
                fixture.admission.tryAcquire().assert()
                    .isEqualTo(BatchAdmissionRejectionReason.QUEUE_SLOTS_EXHAUSTED)
            }
            fixture.scheduler.advanceTimeBy(Duration.ofSeconds(1))
            fixture.batches.assert().containsExactly(listOf(1))

            fixture.releaseWriter()
            fixture.scheduler.advanceTimeBy(Duration.ZERO)
            fixture.submit(10)
            fixture.lane.complete()
            fixture.scheduler.advanceTimeBy(Duration.ZERO)

            fixture.assertDrained(listOf(1, 10))
            repeat(8) { fixture.admission.tryAcquire().assert().isNull() }
            fixture.admission.tryAcquire().assert()
                .isEqualTo(BatchAdmissionRejectionReason.LIVE_ITEMS_EXHAUSTED)
            repeat(8) { fixture.admission.releaseUntracked() }
        }
    }

    @Test
    fun `closed lifecycle should reject admission without accepting the request`() {
        LaneFixture().use { fixture ->
            var accepted = false

            fixture.lane.emitIfOpen({ false }) {
                accepted = true
                error("must not accept")
            }.assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)

            accepted.assert().isFalse()
            fixture.lane.complete()
            fixture.scheduler.advanceTimeBy(Duration.ZERO)
            fixture.assertDrained(emptyList())
        }
    }

    @Test
    fun `admission into a completed lane should discard the request admission`() {
        LaneFixture(maxPendingItems = 4).use { fixture ->
            fixture.lane.complete().assert().isEqualTo(Sinks.EmitResult.OK)
            repeat(4) {
                fixture.admission.tryAcquire().assert().isNull()
                val request = fixture.admission.track(it)

                fixture.lane.emitIfOpen({ true }) {
                    fixture.admission.accept(request)
                    request
                }.assert().isEqualTo(Sinks.EmitResult.FAIL_TERMINATED)
            }

            fixture.admission.pendingSnapshot().assert().isEmpty()
            repeat(4) { fixture.admission.tryAcquire().assert().isNull() }
            repeat(4) { fixture.admission.releaseUntracked() }
        }
    }

    @Test
    fun `admission into one lane should not wait for an admission into another lane`() {
        LaneFixture().use { first ->
            LaneFixture().use { second ->
                val inFirst = CountDownLatch(1)
                val releaseFirst = CountDownLatch(1)
                val firstAdmission = CompletableFuture.supplyAsync {
                    first.lane.emitIfOpen({ true }) {
                        first.admission.tryAcquire().assert().isNull()
                        val request = first.admission.track(1)
                        first.admission.accept(request)
                        inFirst.countDown()
                        releaseFirst.await()
                        request
                    }
                }
                inFirst.await(1, TimeUnit.SECONDS).assert().isTrue()

                CompletableFuture.runAsync { second.submit(2) }.get(1, TimeUnit.SECONDS)

                releaseFirst.countDown()
                firstAdmission.get(1, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK)
                listOf(first, second).forEach {
                    it.lane.complete()
                    it.scheduler.advanceTimeBy(Duration.ofMillis(1))
                    it.releaseWriter()
                    it.scheduler.advanceTimeBy(Duration.ZERO)
                }
                first.assertDrained(listOf(1))
                second.assertDrained(listOf(2))
            }
        }
    }

    @Test
    fun `awaiting admissions should wait for an admission in progress`() {
        LaneFixture().use { fixture ->
            val inAdmission = CountDownLatch(1)
            val releaseAdmission = CountDownLatch(1)
            val admission = CompletableFuture.supplyAsync {
                fixture.lane.emitIfOpen({ true }) {
                    fixture.admission.tryAcquire().assert().isNull()
                    val request = fixture.admission.track(1)
                    fixture.admission.accept(request)
                    inAdmission.countDown()
                    releaseAdmission.await()
                    request
                }
            }
            inAdmission.await(1, TimeUnit.SECONDS).assert().isTrue()
            val awaited = CompletableFuture.runAsync(fixture.lane::awaitAdmissions)

            Thread.sleep(50)
            awaited.isDone.assert().isFalse()
            releaseAdmission.countDown()

            awaited.get(1, TimeUnit.SECONDS)
            admission.get(1, TimeUnit.SECONDS).assert().isEqualTo(Sinks.EmitResult.OK)
            fixture.admission.pendingSnapshot().map { it.value }.assert().containsExactly(1)
        }
    }

    @Test
    fun `partial batch should flush on timeout when writer has demand`() {
        LaneFixture().use { fixture ->
            fixture.submit(1)
            fixture.scheduler.advanceTimeBy(Duration.ofNanos(999_999))
            fixture.batches.assert().isEmpty()
            fixture.scheduler.advanceTimeBy(Duration.ofNanos(1))
            fixture.batches.assert().containsExactly(listOf(1))
            fixture.releaseWriter()
            fixture.lane.complete()
            fixture.scheduler.advanceTimeBy(Duration.ZERO)
            fixture.assertDrained(listOf(1))
        }
    }

    private class LaneFixture(maxPendingItems: Int = 64) : AutoCloseable {
        val scheduler = VirtualTimeScheduler.create()
        val admission = BatchAdmission<Int>(maxPendingItems, null)
        val batches = mutableListOf<List<Int>>()
        private val settled = mutableListOf<Int>()
        private val writerGate = Sinks.empty<Void>()
        private var failure: Throwable? = null
        var completed = false
            private set
        val lane = BatchLane(
            name = "demand-aware-test",
            lane = 0,
            options = BatchOptions(4, Duration.ofMillis(1), maxPendingItems),
            writer = BatchWriter<Int> { items ->
                batches.add(items.toList())
                val outcomes: List<BatchItemResult> = items.map { BatchItemResult.Success }
                if (batches.size == 1) writerGate.asMono().thenReturn(outcomes) else Mono.just(outcomes)
            },
            scheduler = scheduler,
            settle = { requests, outcomes ->
                requests.forEachIndexed { index, request ->
                    request.settle(outcomes[index])
                    request.signalSettled()
                    settled.add(request.value)
                }
            },
            metrics = null,
            onError = { failure = it },
            onComplete = { completed = true },
        )

        fun submit(value: Int): BatchRequest<Int> {
            admission.tryAcquire().assert().isNull()
            val request = admission.track(value)
            lane.emitIfOpen({ true }) {
                admission.accept(request)
                request
            }.assert().isEqualTo(Sinks.EmitResult.OK)
            return request
        }

        fun releaseWriter() {
            writerGate.tryEmitEmpty().assert().isEqualTo(Sinks.EmitResult.OK)
        }

        fun assertDrained(expected: List<Int>) {
            failure.assert().isNull()
            completed.assert().isTrue()
            batches.flatten().assert().isEqualTo(expected)
            settled.assert().isEqualTo(expected)
            admission.pendingSnapshot().assert().isEmpty()
        }

        override fun close() {
            writerGate.tryEmitEmpty()
            lane.dispose()
            scheduler.advanceTimeBy(Duration.ZERO)
            scheduler.dispose()
        }
    }
}
