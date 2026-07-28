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

package me.ahoo.wow

import me.ahoo.test.asserts.assert
import me.ahoo.wow.modeling.toNamedAggregate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BenchmarkAggregateSchedulerSupplierTest {
    @Test
    fun `stopped supplier rejects scheduler reinitialization`() {
        val supplier = BenchmarkAggregateSchedulerSupplier(schedulerPoolSize = 1)
        val scheduler = supplier.getOrInitialize("benchmark.Cart".toNamedAggregate())

        supplier.stopGracefully().block()

        scheduler.isDisposed.assert().isTrue()
        assertThrows<IllegalStateException> {
            supplier.getOrInitialize("benchmark.Order".toNamedAggregate())
        }
    }

    @Test
    fun `force stop takes over shared graceful termination`() {
        val supplier = BenchmarkAggregateSchedulerSupplier(schedulerPoolSize = 1)
        val scheduler = supplier.getOrInitialize("benchmark.Cart".toNamedAggregate())
        val taskStarted = CountDownLatch(1)
        val taskInterrupted = CountDownLatch(1)
        val releaseTask = CountDownLatch(1)
        scheduler.schedule {
            taskStarted.countDown()
            try {
                releaseTask.await()
            } catch (_: InterruptedException) {
                taskInterrupted.countDown()
                Thread.currentThread().interrupt()
            }
        }
        taskStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

        val firstObserver = supplier.stopGracefully().toFuture()
        val secondObserver = supplier.stopGracefully().toFuture()

        try {
            firstObserver.isDone.assert().isFalse()
            secondObserver.isDone.assert().isFalse()

            supplier.forceStop()

            taskInterrupted.await(1, TimeUnit.SECONDS).assert().isTrue()
            firstObserver.get(1, TimeUnit.SECONDS)
            secondObserver.get(1, TimeUnit.SECONDS)
        } finally {
            releaseTask.countDown()
            supplier.forceStop()
        }
    }
}
