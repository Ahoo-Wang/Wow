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

package me.ahoo.wow.benchmark.load

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * An allocation read waits a bounded time for the JVM's answer (#7109): one whose answer never comes gives no figure,
 * and says why, instead of holding the run past its drain.
 */
class AllocationsTest {
    private val closed = CountDownLatch(1)
    private val connector = AutoCloseable { closed.countDown() }

    @Test
    fun `a read that answers should give the figure`() {
        Allocations.Reader(connector, { 42L }).use { reader ->
            reader.read().assert().isEqualTo(42L)
            reader.missing.assert().isNull()
        }
    }

    @Test
    fun `a read that fails should give no figure and say why`() {
        Allocations.Reader(connector, { error("the JVM is gone") }).use { reader ->
            reader.read().assert().isNull()
            reader.missing.assert().contains("cannot read the allocations").contains("the JVM is gone")
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS) // Fails, rather than holds the build, should the bound go.
    fun `a read that does not answer should give the figure up at its bound and say so`() {
        val never = CountDownLatch(1)
        val hanging = {
            never.await()
            1L
        }
        Allocations.Reader(connector, hanging, timeout = 100.milliseconds).use { reader ->
            reader.read().assert().isNull()
            reader.missing.assert().isEqualTo("the allocation read did not answer in 100ms")
        }
        // Closed, the connection that holds the call is closed too.
        closed.count.assert().isZero()
    }
}
