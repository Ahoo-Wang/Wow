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

package me.ahoo.wow.infra.idempotency

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class BloomFilterIdempotencyCheckerTest {

    @Test
    fun `should allow first element and reject duplicate element in cached filter`() {
        val creations = AtomicInteger()
        val checker = BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
            creations.incrementAndGet()
            BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 100)
        }

        checker.check("request-1").assert().isTrue()
        checker.check("request-1").assert().isFalse()
        creations.get().assert().isEqualTo(1)
    }

    @Test
    fun `should refresh cached filter after ttl expires`() {
        val creations = AtomicInteger()
        val checker = BloomFilterIdempotencyChecker(Duration.ZERO) {
            creations.incrementAndGet()
            BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 100)
        }

        checker.check("request-1").assert().isTrue()
        checker.check("request-1").assert().isTrue()
        creations.get().assert().isGreaterThan(1)
    }

    @Test
    fun `should treat negative ttl as expired cache`() {
        val creations = AtomicInteger()
        val checker = BloomFilterIdempotencyChecker(Duration.ofNanos(-1)) {
            creations.incrementAndGet()
            BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 100)
        }

        checker.check("request-1").assert().isTrue()
        checker.check("request-1").assert().isTrue()
        creations.get().assert().isGreaterThan(1)
    }

    @Test
    fun `should reuse filter initialized by another thread while waiting for refresh lock`() {
        val supplierEntered = CountDownLatch(1)
        val releaseSupplier = CountDownLatch(1)
        val creations = AtomicInteger()
        val firstResult = AtomicReference<Boolean>()
        val secondResult = AtomicReference<Boolean>()
        val checker = BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
            creations.incrementAndGet()
            supplierEntered.countDown()
            releaseSupplier.await(5, TimeUnit.SECONDS)
            BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 100)
        }

        val first = Thread {
            firstResult.set(checker.check("request-1"))
        }.also { it.start() }
        supplierEntered.await(5, TimeUnit.SECONDS).assert().isTrue()

        val second = Thread {
            secondResult.set(checker.check("request-2"))
        }.also { it.start() }
        try {
            awaitBlocked(second)
        } finally {
            releaseSupplier.countDown()
        }

        first.join(5000)
        second.join(5000)

        first.isAlive.assert().isFalse()
        second.isAlive.assert().isFalse()
        firstResult.get().assert().isTrue()
        secondResult.get().assert().isTrue()
        creations.get().assert().isEqualTo(1)
    }

    @Test
    fun `should allow the same element only once under concurrent checks`() {
        val parallelism = 32
        val executor = Executors.newFixedThreadPool(parallelism)
        try {
            repeat(100) { round ->
                val checker = BloomFilterIdempotencyChecker(Duration.ofMinutes(1)) {
                    BloomFilter.create(Funnels.stringFunnel(Charsets.UTF_8), 100_000)
                }
                checker.check("warmup-$round").assert().isTrue()
                val barrier = CyclicBarrier(parallelism)
                val allowed = executor.invokeAll(
                    List(parallelism) {
                        Callable {
                            barrier.await(5, TimeUnit.SECONDS)
                            checker.check("request-$round")
                        }
                    },
                ).count { result -> result.get() }

                allowed.assert().isEqualTo(1)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield()
        }
        thread.state.assert().isEqualTo(Thread.State.BLOCKED)
    }
}
