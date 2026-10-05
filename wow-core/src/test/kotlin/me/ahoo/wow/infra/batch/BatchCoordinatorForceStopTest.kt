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
import reactor.core.publisher.Mono
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BatchCoordinatorForceStopTest {
    @Test
    fun `force stop fails a hanging graceful stop and its pending callers without waiting`() {
        val writerSubscribed = CountDownLatch(1)
        val coordinator = coordinator(maxPendingItems = 2) {
            writerSubscribed.countDown()
            Mono.never()
        }
        // Two items fill the window, so the writer starts and hangs.
        val pending = coordinator.submit(1).materialize().toFuture()
        val second = coordinator.submit(2).materialize().toFuture()
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()
        val graceful = coordinator.stopGracefully().materialize().toFuture()

        coordinator.forceStop()

        pending.get(1, TimeUnit.SECONDS)!!.throwable.assert().isInstanceOf(BatchClosedException::class.java)
        second.get(1, TimeUnit.SECONDS)!!.throwable.assert().isInstanceOf(BatchClosedException::class.java)
        graceful.get(1, TimeUnit.SECONDS)!!.throwable.assert().isInstanceOf(BatchClosedException::class.java)
        coordinator.submit(3).test().expectError(BatchClosedException::class.java).verify()
    }

    @Test
    fun `close after a force stop returns quietly, every time`() {
        val writerSubscribed = CountDownLatch(1)
        val coordinator = coordinator(maxPendingItems = 2) {
            writerSubscribed.countDown()
            Mono.never()
        }
        val pending = coordinator.submit(1).materialize().toFuture()
        coordinator.submit(2).subscribe({}, {})
        writerSubscribed.await(1, TimeUnit.SECONDS).assert().isTrue()

        coordinator.forceStop()
        coordinator.forceStop()

        // Spring closes the store and the appender it wraps: the same coordinator twice.
        coordinator.close()
        coordinator.close()
        coordinator.stop()
        pending.get(1, TimeUnit.SECONDS)!!.throwable.assert().isInstanceOf(BatchClosedException::class.java)
    }

    @Test
    fun `close after another failure still throws it`() {
        val coordinator = coordinator(maxPendingItems = 2) { Mono.never() }
        coordinator.submit(1).subscribe({}, {})
        coordinator.submit(2).subscribe({}, {})

        assertThrows<BatchCloseTimeoutException> { coordinator.close(Duration.ofMillis(50)) }
        coordinator.forceStop()

        assertThrows<BatchCloseTimeoutException> { coordinator.close() }
    }

    @Test
    fun `force stop after a completed graceful stop changes nothing`() {
        val coordinator = coordinator { items -> Mono.just(items.map { BatchItemResult.Success }) }
        val result = coordinator.submit(1).materialize().toFuture()
        coordinator.stopGracefully().test().verifyComplete()

        coordinator.forceStop()

        result.get(1, TimeUnit.SECONDS)!!.isOnComplete.assert().isTrue()
        coordinator.close()
    }
}
