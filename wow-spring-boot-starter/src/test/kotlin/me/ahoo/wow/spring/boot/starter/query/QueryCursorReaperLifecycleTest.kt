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

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.scheduler.VirtualTimeScheduler
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class QueryCursorReaperLifecycleTest {
    @Test
    fun `should drain only a bounded number of batches per scheduled run`() {
        val scheduler = VirtualTimeScheduler.create()
        val calls = AtomicInteger()
        val lifecycle = QueryCursorReaperLifecycle(
            reap = {
                calls.incrementAndGet()
                Mono.just(2)
            },
            properties = QueryCursorReaperProperties(
                enabled = true,
                initialDelay = Duration.ofSeconds(1),
                interval = Duration.ofSeconds(10),
                batchSize = 2,
                maxBatchesPerRun = 3,
            ),
            scheduler = scheduler,
            ownsScheduler = false,
        )

        lifecycle.start()
        scheduler.advanceTimeBy(Duration.ofSeconds(1))

        lifecycle.isRunning.assert().isTrue()
        calls.get().assert().isEqualTo(3)

        lifecycle.stop()
        scheduler.advanceTimeBy(Duration.ofMinutes(1))
        lifecycle.isRunning.assert().isFalse()
        calls.get().assert().isEqualTo(3)
    }

    @Test
    fun `should isolate one failed run and continue at the next interval`() {
        val scheduler = VirtualTimeScheduler.create()
        val calls = AtomicInteger()
        val lifecycle = QueryCursorReaperLifecycle(
            reap = {
                if (calls.getAndIncrement() == 0) {
                    Mono.error(IllegalStateException("cursor store unavailable"))
                } else {
                    Mono.just(0)
                }
            },
            properties = QueryCursorReaperProperties(
                enabled = true,
                initialDelay = Duration.ofSeconds(1),
                interval = Duration.ofSeconds(10),
                batchSize = 2,
                maxBatchesPerRun = 3,
            ),
            scheduler = scheduler,
            ownsScheduler = false,
        )

        lifecycle.start()
        scheduler.advanceTimeBy(Duration.ofSeconds(1))
        calls.get().assert().isEqualTo(1)

        scheduler.advanceTimeBy(Duration.ofSeconds(10))
        calls.get().assert().isEqualTo(2)
        lifecycle.isRunning.assert().isTrue()

        lifecycle.stop()
    }

    @Test
    fun `should stop after a short terminal batch`() {
        val scheduler = VirtualTimeScheduler.create()
        val calls = AtomicInteger()
        val results = ArrayDeque(listOf(2L, 1L))
        val lifecycle = QueryCursorReaperLifecycle(
            reap = {
                calls.incrementAndGet()
                Mono.just(results.removeFirst())
            },
            properties = QueryCursorReaperProperties(
                enabled = true,
                initialDelay = Duration.ofSeconds(1),
                interval = Duration.ofSeconds(10),
                batchSize = 2,
                maxBatchesPerRun = 3,
            ),
            scheduler = scheduler,
            ownsScheduler = false,
        )

        lifecycle.start()
        scheduler.advanceTimeBy(Duration.ofSeconds(1))

        calls.get().assert().isEqualTo(2)
        lifecycle.stop()
    }
}
