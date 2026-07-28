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

package me.ahoo.wow.runtime.internal.compat

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.internal.DefaultRuntimeContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StandaloneRuntimeOwnerTest {

    @Test
    fun `force overlapping manual prepare compensates late acquisition`() {
        val prepareEntered = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val resourceOpen = AtomicBoolean()
        val startCount = AtomicInteger()
        val forceCount = AtomicInteger()
        val owner = StandaloneRuntimeOwner(
            prepareAction = {
                prepareEntered.countDown()
                releasePrepare.await()
                resourceOpen.set(true)
            },
            startAction = startCount::incrementAndGet,
            gracefulStopAction = Mono<Void>::empty,
            forceStopAction = {
                forceCount.incrementAndGet()
                resourceOpen.set(false)
            },
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val preparation = executor.submit {
                owner.prepare(DefaultRuntimeContext())
            }
            prepareEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            owner.forceStop()
            forceCount.get().assert().isOne()
            releasePrepare.countDown()
            preparation.get(1, TimeUnit.SECONDS)

            forceCount.get().assert().isEqualTo(2)
            resourceOpen.get().assert().isFalse()
            assertThrows<IllegalStateException>(owner::start)
            startCount.get().assert().isZero()
        } finally {
            releasePrepare.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `force overlapping manual start compensates late acquisition`() {
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val resourceOpen = AtomicBoolean()
        val forceCount = AtomicInteger()
        val owner = StandaloneRuntimeOwner(
            prepareAction = {},
            startAction = {
                startEntered.countDown()
                releaseStart.await()
                resourceOpen.set(true)
            },
            gracefulStopAction = Mono<Void>::empty,
            forceStopAction = {
                forceCount.incrementAndGet()
                resourceOpen.set(false)
            },
        )
        owner.prepare(DefaultRuntimeContext())
        val executor = Executors.newSingleThreadExecutor()

        try {
            val startup = executor.submit(owner::start)
            startEntered.await(1, TimeUnit.SECONDS).assert().isTrue()

            owner.forceStop()
            forceCount.get().assert().isOne()
            releaseStart.countDown()
            startup.get(1, TimeUnit.SECONDS)

            forceCount.get().assert().isEqualTo(2)
            resourceOpen.get().assert().isFalse()
        } finally {
            releaseStart.countDown()
            executor.shutdownNow()
        }
    }
}
