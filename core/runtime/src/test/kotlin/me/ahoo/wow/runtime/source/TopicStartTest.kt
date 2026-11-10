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

package me.ahoo.wow.runtime.source

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.spi.state.StateEventRecord
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.api.Test
import java.time.Instant

/** Where a subscription of a published topic starts from an instant: the offset of the first record at or after it. */
class TopicStartTest {
    private val topics = InMemoryTopics(1).also { it.create(ORDER_TOPIC, 1) }
    private val entries = TopicEntries(ORDER_TOPIC, topics, 1)

    private suspend fun at(millis: Long): Long = entries.checkpointOf(
        SubscriptionStart.At(Instant.ofEpochMilli(millis)),
        0
    )

    /** The record of `o-1` at [version], committed at [second] seconds. */
    private fun committedAt(version: Int, second: Long): StateEventRecord =
        orderRecord("o-1", version).copyWith(commitTime = second * 1_000)

    @Test
    fun `an empty partition starts at its end`(): Unit = runBlocking {
        at(1_000).assert().isEqualTo(0L)
    }

    @Test
    fun `an instant starts at the first record committed at or after it, at the end when none is`(): Unit =
        runBlocking {
            topics.publish(*(1..4).map { orderRecord("o-1", it) }.toTypedArray())
            // Before every record: the first offset.
            at(0).assert().isEqualTo(0L)
            at(1_000).assert().isEqualTo(0L)
            // The record at offset 2 is committed at 3 s.
            at(2_500).assert().isEqualTo(2L)
            at(4_000).assert().isEqualTo(3L)
            // After every record: the end offset.
            at(4_001).assert().isEqualTo(4L)
        }

    @Test
    fun `an instant starts at the first record in offset order committed at or after it, whatever the times after it`():
        Unit = runBlocking {
        topics.publish(committedAt(1, 5), committedAt(2, 1), committedAt(3, 7), committedAt(4, 3))
        at(4_000).assert().isEqualTo(0L)
        at(6_000).assert().isEqualTo(2L)
        at(8_000).assert().isEqualTo(4L)
    }

    @Test
    fun `records it cannot decode are passed over, so a partition of nothing else starts at its end`(): Unit =
        runBlocking {
            repeat(3) { topics.send(ORDER_TOPIC, listOf(TopicMessage.of("not a ref", UNDECODABLE.toByteArray()))) }
            at(0).assert().isEqualTo(3L)
            topics.publish(orderRecord("o-1", 2))
            at(0).assert().isEqualTo(3L)
        }

    private companion object {
        /** An envelope holding no state event this version reads. */
        const val UNDECODABLE = """{"format":2,"kind":"state-event","id":"sales.order/t/o-1@2","payload":{}}"""
    }
}
