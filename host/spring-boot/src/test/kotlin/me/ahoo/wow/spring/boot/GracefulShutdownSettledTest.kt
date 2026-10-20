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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.context.SmartLifecycle
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds

/** The public server's stop waits for its graceful shutdown's thread to end (review X-4). */
class GracefulShutdownSettledTest {
    private fun shutdownThread(name: String = GracefulShutdownSettled.SHUTDOWN_THREAD, body: () -> Unit) =
        Thread(body, name).apply { start() }

    @Test
    fun `the stop should wait for the graceful shutdown's thread to end`() {
        val shutdown = shutdownThread { Thread.sleep(200) }
        val settled = GracefulShutdownSettled(threads = { listOf(shutdown) })
        settled.start()
        settled.isRunning.assert().isTrue()
        settled.stop()
        shutdown.isAlive.assert().isFalse()
        settled.isRunning.assert().isFalse()
    }

    @Test
    fun `the wait should be bounded, and leave other threads alone`() {
        val release = CountDownLatch(1)
        val stuck = shutdownThread { release.await() }
        val other = shutdownThread("worker") { release.await() }
        try {
            val started = System.nanoTime()
            GracefulShutdownSettled(100.milliseconds) { listOf(stuck, other) }.stop()
            ((System.nanoTime() - started) / 1_000_000).assert().isLessThan(2_000)
            stuck.isAlive.assert().isTrue()
        } finally {
            release.countDown()
        }
        GracefulShutdownSettled().stop()
    }

    @Test
    fun `it should stop between the graceful shutdown and the server's stop`() {
        val phase = GracefulShutdownSettled().phase
        phase.assert().isLessThan(SmartLifecycle.DEFAULT_PHASE - 1024).isGreaterThan(WowLifecycles.PUBLIC_SERVER_PHASE)
    }
}
