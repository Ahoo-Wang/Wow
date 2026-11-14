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

package me.ahoo.wow.runtime.subscription

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** The handlings a worker runs as its stop counts them: each leaves once, a handling given up as early as it is. */
class RunningHandlingsTest {
    @Test
    fun `a handling given up leaves at once and its end leaves nothing more`(): Unit = runBlocking {
        // Certain as every step here: one thread, each call in order.
        val running = RunningHandlings()
        running.started()
        running.started()
        val waiting = async { running.awaitNone() }
        running.stop()
        // x's retry is given up: it leaves now, and the stop waits for y alone.
        running.givenUp(cart("x"))
        running.givenUp(cart("x"))
        running.value.assert().isEqualTo(1)
        yield()
        waiting.isCompleted.assert().isFalse()
        running.ended(cart("y"))
        withTimeout(10.seconds) { waiting.await() }
        // x's lane is cancelled: its end leaves nothing more.
        running.ended(cart("x"))
        running.value.assert().isEqualTo(0)
        running.stop.isCompleted.assert().isTrue()
    }

    @Test
    fun `a handling that ends before any stop leaves once`(): Unit = runBlocking {
        val running = RunningHandlings()
        running.started()
        running.ended(cart("x"))
        running.value.assert().isEqualTo(0)
        running.awaitNone()
        running.stop.isCompleted.assert().isFalse()
    }
}
