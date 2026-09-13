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
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BatchResultDispatcherTest {
    @Test
    fun `last notification while open should release its worker without taking drain lock`() {
        val lock = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nextTask = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val dispatcher = BatchResultDispatcher("open-last", 1, lock, drained::countDown)
        val request = request(1)
        request.result.asMono().doOnSuccess {
            entered.countDown()
            release.await()
        }.subscribe()
        try {
            publish(dispatcher, lock, request)
            entered.await(1, TimeUnit.SECONDS).assert().isTrue()
            synchronized(lock) {
                release.countDown()
                executor(dispatcher).execute { nextTask.countDown() }
                nextTask.await(1, TimeUnit.SECONDS).assert().isTrue()
            }
            drained.count.assert().isEqualTo(1L)
        } finally {
            release.countDown()
            synchronized(lock) { dispatcher.seal() }
            dispatcher.shutdown()
            drained.await(1, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `nonfinal notification should release its worker without taking result lock`() {
        val lock = Any()
        val entered = CountDownLatch(2)
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val thirdEntered = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val dispatcher = BatchResultDispatcher("counting", 2, lock, drained::countDown)
        val first = request(1)
        val second = request(2)
        val third = request(3)
        first.result.asMono().doOnSuccess {
            entered.countDown()
            releaseFirst.await()
        }.subscribe()
        second.result.asMono().doOnSuccess {
            entered.countDown()
            releaseSecond.await()
        }.subscribe()
        third.result.asMono().doOnSuccess { thirdEntered.countDown() }.subscribe()
        try {
            listOf(first, second, third).forEach { publish(dispatcher, lock, it) }
            entered.await(1, TimeUnit.SECONDS).assert().isTrue()
            synchronized(lock) {
                releaseFirst.countDown()
                thirdEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            }
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            synchronized(lock) { dispatcher.seal() }
            dispatcher.shutdown()
            drained.await(1, TimeUnit.SECONDS).assert().isTrue()
        }
    }

    @Test
    fun `settlement and publication should exclude a concurrent failure seal`() {
        val lock = Any()
        val settled = CountDownLatch(1)
        val releasePublisher = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val emissions = AtomicInteger()
        val dispatcher = BatchResultDispatcher("race", 2, lock, drained::countDown)
        val request = request(1)
        val result = request.result.asMono().doOnSuccess { emissions.incrementAndGet() }.toFuture()
        val publish = CompletableFuture.runAsync {
            synchronized(lock) {
                request.settle(BatchItemResult.Success)
                settled.countDown()
                releasePublisher.await()
                dispatcher.publish(request)
            }
        }
        try {
            settled.await(1, TimeUnit.SECONDS).assert().isTrue()
            val close = CompletableFuture.runAsync {
                closeStarted.countDown()
                synchronized(lock) {
                    request.settleFailureIfUnsettled(IllegalStateException("close"))
                        .assert().isFalse()
                    dispatcher.publish(request)
                    dispatcher.seal()
                }
                dispatcher.shutdown()
            }
            closeStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
            close.isDone.assert().isFalse()
            releasePublisher.countDown()
            publish.get(1, TimeUnit.SECONDS)
            close.get(1, TimeUnit.SECONDS)
            result.get(1, TimeUnit.SECONDS)
            drained.await(1, TimeUnit.SECONDS).assert().isTrue()
            emissions.get().assert().isEqualTo(1)
        } finally {
            releasePublisher.countDown()
            synchronized(lock) { dispatcher.seal() }
            dispatcher.shutdown()
        }
    }

    @Test
    fun `saturated notifications should queue once and drain after blocked callbacks exit`() {
        val lock = Any()
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val emissions = AtomicInteger()
        val dispatcher = BatchResultDispatcher("test", 8, lock, drained::countDown)
        val requests = (1..12).map { request(it) }
        requests.forEachIndexed { index, request ->
            request.result.asMono().doOnSuccess {
                dispatcher.isDispatchingResult.assert().isTrue()
                emissions.incrementAndGet()
                if (index < 4) {
                    entered.countDown()
                    release.await()
                }
            }.subscribe()
        }
        try {
            requests.take(4).forEach { publish(dispatcher, lock, it) }
            entered.await(1, TimeUnit.SECONDS).assert().isTrue()
            requests.drop(4).forEach { publish(dispatcher, lock, it) }
            executor(dispatcher).queue.size.assert().isEqualTo(8)
            synchronized(lock) {
                requests.forEach(dispatcher::publish)
                dispatcher.seal().assert().isTrue()
                // Pause after sealing and before physical shutdown; late results stay ignored.
                requests.forEach(dispatcher::publish)
                dispatcher.seal().assert().isFalse()
            }
            dispatcher.shutdown()
            drained.count.assert().isEqualTo(1L)
            release.countDown()
            drained.await(1, TimeUnit.SECONDS).assert().isTrue()
            emissions.get().assert().isEqualTo(12)
        } finally {
            release.countDown()
            synchronized(lock) { dispatcher.seal() }
            dispatcher.shutdown()
        }
    }

    @Test
    fun `drain observer may wait for another thread to acquire result lock`() {
        val lock = Any()
        val observed = CountDownLatch(1)
        val dispatcher = BatchResultDispatcher("empty", 2, lock) {
            Thread.holdsLock(lock).assert().isFalse()
            CompletableFuture.runAsync { synchronized(lock) { observed.countDown() } }
                .get(1, TimeUnit.SECONDS)
        }
        synchronized(lock) { dispatcher.seal() }
        dispatcher.shutdown()
        observed.count.assert().isEqualTo(0L)
    }

    @Test
    fun `last notification finishing before shutdown should drain only after shutdown`() {
        val lock = Any()
        val drained = CountDownLatch(1)
        val emitted = CountDownLatch(1)
        val dispatcher = BatchResultDispatcher("last", 1, lock, drained::countDown)
        val request = request(1)
        request.result.asMono().doOnSuccess { emitted.countDown() }.subscribe()
        publish(dispatcher, lock, request)
        emitted.await(1, TimeUnit.SECONDS).assert().isTrue()
        // One worker guarantees this marker runs after the notification task's finally.
        executor(dispatcher).submit {}.get(1, TimeUnit.SECONDS)
        synchronized(lock) { dispatcher.seal() }
        drained.count.assert().isEqualTo(1L)
        dispatcher.shutdown()
        drained.await(1, TimeUnit.SECONDS).assert().isTrue()
    }

    @Test
    fun `unavailable executor should reject unique publication without inline emission`() {
        val lock = Any()
        val dispatcher = BatchResultDispatcher("broken", 2, lock) {}
        val request = request(1)
        val emissions = AtomicInteger()
        request.result.asMono().doOnSuccess { emissions.incrementAndGet() }.subscribe()
        executor(dispatcher).shutdown()
        synchronized(lock) {
            request.settle(BatchItemResult.Success)
            assertThrows<RejectedExecutionException> { dispatcher.publish(request) }
            // Failed publication rolls back the notification claim.
            assertThrows<RejectedExecutionException> { dispatcher.publish(request) }
            dispatcher.seal()
        }
        dispatcher.shutdown()
        emissions.get().assert().isEqualTo(0)
    }

    private fun request(value: Int): BatchRequest<Int> = BatchRequest(value, {}, {}).also { it.claim() }

    private fun publish(dispatcher: BatchResultDispatcher, lock: Any, request: BatchRequest<Int>) {
        synchronized(lock) {
            request.settle(BatchItemResult.Success)
            dispatcher.publish(request)
        }
    }

    private fun executor(dispatcher: BatchResultDispatcher): ThreadPoolExecutor =
        BatchResultDispatcher::class.java.getDeclaredField("executor").let {
            it.isAccessible = true
            it.get(dispatcher) as ThreadPoolExecutor
        }
}
