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

package me.ahoo.wow.mongo

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Finding A2-14: an idle tail backs off, bounded, and a commit of the log wakes it at once. */
class TailPacerTest {
    @Test
    fun `an idle tail backs off up to the longest interval, and entries bring it back`(): Unit = runBlocking {
        val pacer = TailPacer(2, 1.milliseconds, 6.milliseconds)
        var idle = pacer.interval
        val waits = (1..4).map {
            idle = pacer.await(0, pacer.mark(0), found = false, idle = idle)
            idle
        }
        waits.assert().containsExactly(2.milliseconds, 4.milliseconds, 6.milliseconds, 6.milliseconds)
        pacer.await(0, pacer.mark(0), found = true, idle = idle).assert().isEqualTo(1.milliseconds)
    }

    @Test
    fun `a commit of the log in the partition wakes its tail at once`(): Unit = runBlocking {
        val pacer = TailPacer(2, 1.milliseconds, 10.minutes)
        val mark = pacer.mark(1)
        val waiting = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            pacer.await(1, mark, found = false, idle = 10.minutes)
        }
        // A commit in another partition does not wake it.
        pacer.committed(0)
        delay(50)
        waiting.isCompleted.assert().isFalse()
        pacer.committed(1)
        withTimeout(10.seconds) { waiting.await() }.assert().isEqualTo(10.minutes)
        // A commit between the poll and the wait is not missed.
        val before = pacer.mark(1)
        pacer.committed(1)
        withTimeout(10.seconds) { pacer.await(1, before, found = false, idle = 10.minutes) }
    }

    @Test
    fun `the intervals are positive and ordered`() {
        assertThrows<IllegalArgumentException> { TailPacer(1, 0.milliseconds, 1.seconds) }
        assertThrows<IllegalArgumentException> { TailPacer(1, 2.seconds, 1.seconds) }
    }
}
