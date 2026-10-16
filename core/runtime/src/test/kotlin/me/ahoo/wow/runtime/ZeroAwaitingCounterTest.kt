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

package me.ahoo.wow.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ZeroAwaitingCounterTest {
    @Test
    fun `a counter at zero is awaited at once`(): Unit = runBlocking {
        val counter = ZeroAwaitingCounter()
        withTimeout(TIMEOUT_MS) { counter.awaitZero() }
        counter.value.assert().isEqualTo(0)
    }

    @Test
    fun `waiters resume once the last decrement brings it to zero, and again later`(): Unit = runBlocking {
        val counter = ZeroAwaitingCounter()
        repeat(2) { counter.increment() }
        val waiters = (1..4).map { async(Dispatchers.Default) { counter.awaitZero() } }
        yield()
        counter.decrement()
        waiters.any { it.isCompleted }.assert().isFalse()
        counter.decrement()
        withTimeout(TIMEOUT_MS) { waiters.awaitAll() }

        counter.set(3)
        val again = async(Dispatchers.Default) { counter.awaitZero() }
        counter.set(0)
        withTimeout(TIMEOUT_MS) { again.await() }
    }

    @Test
    fun `concurrent updates and waiters never lose the zero`(): Unit = runBlocking {
        val counter = ZeroAwaitingCounter()
        repeat(ROUNDS) {
            repeat(WORKERS) { counter.increment() }
            val waiter = async(Dispatchers.Default) { counter.awaitZero() }
            repeat(WORKERS) { launch(Dispatchers.Default) { counter.decrement() } }
            withTimeout(TIMEOUT_MS) { waiter.await() }
        }
        counter.value.assert().isEqualTo(0)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val ROUNDS = 200
        const val WORKERS = 8
    }
}
