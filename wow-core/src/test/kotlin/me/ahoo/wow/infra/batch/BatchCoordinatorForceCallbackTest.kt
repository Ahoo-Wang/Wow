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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BatchCoordinatorForceCallbackTest {

    @Test
    fun `force stop never runs abandoned result callbacks on its caller`() {
        val itemCount = 16
        val blockingWorkerCount = 4
        val writerSubscribed = CountDownLatch(1)
        val callbacksEntered = CountDownLatch(blockingWorkerCount)
        val releaseCallbacks = CountDownLatch(1)
        val callbackThreads = ConcurrentHashMap.newKeySet<String>()
        val forceExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-force-caller")
        }
        val coordinator = coordinator(
            maxSize = itemCount,
            maxPendingItems = itemCount,
        ) {
            Mono.never<List<BatchItemResult>>()
                .doOnSubscribe { writerSubscribed.countDown() }
        }
        val callers = (1..itemCount).map { item ->
            coordinator.submit(item)
                .doOnError {
                    callbackThreads += Thread.currentThread().name
                    callbacksEntered.countDown()
                    awaitIgnoringInterrupt(releaseCallbacks)
                }.materialize()
                .toFuture()
        }
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        val termination = coordinator.stopGracefully().materialize().toFuture()

        try {
            val forceStop = CompletableFuture.runAsync(coordinator::forceStop, forceExecutor)

            callbacksEntered.await(1, TimeUnit.SECONDS).assert().isTrue()
            forceStop.get(250, TimeUnit.MILLISECONDS)
            termination.get(1, TimeUnit.SECONDS)!!.throwable
                .assert()
                .isInstanceOf(BatchClosedException::class.java)
            coordinator.pendingItemCount.assert().isZero()
            callbackThreads.assert().doesNotContain("test-force-caller")
        } finally {
            releaseCallbacks.countDown()
            callers.forEach { caller ->
                caller.get(1, TimeUnit.SECONDS)
            }
            forceExecutor.shutdownNow()
        }
    }

    private fun awaitIgnoringInterrupt(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Simulate an uncooperative subscriber callback.
            }
        }
    }
}
