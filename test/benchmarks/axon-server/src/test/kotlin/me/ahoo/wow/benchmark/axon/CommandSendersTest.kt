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

package me.ahoo.wow.benchmark.axon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CommandSendersTest {
    @Test
    fun `it sends on its platform threads, as many at once as it has, and queues the rest`() {
        val senders = CommandSenders(2)
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val threads = ConcurrentLinkedQueue<Thread>()
        try {
            repeat(3) {
                senders.execute {
                    threads += Thread.currentThread()
                    started.countDown()
                    release.await()
                }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS), "the first two did not start")
            assertEquals(1, senders.queued)
            assertTrue(threads.none { it.isVirtual }, "$threads")
            assertEquals(setOf("command-1", "command-2"), threads.map { it.name }.toSet())
        } finally {
            release.countDown()
            senders.destroy()
        }
    }

    @Test
    fun `the transfer processor has a segment per CPU, at most 16`() {
        assertEquals(1, segmentsFor(1))
        assertEquals(2, segmentsFor(2))
        assertEquals(16, segmentsFor(16))
        assertEquals(16, segmentsFor(64))
    }
}
