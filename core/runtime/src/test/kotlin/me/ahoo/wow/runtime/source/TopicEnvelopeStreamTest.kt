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

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.subscription.UndecodableMessage
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.Test

/**
 * A Wow envelope that decodes into a state event but not into a stream (its access attributes lack the built-in owner
 * and space, which an event stream requires) is a record that cannot be decoded: the tail hands it as an undecodable
 * entry, which parks its aggregate, rather than failing the partition's read for every subscription of the topic
 * (#7159).
 */
class TopicEnvelopeStreamTest : TopicSubscriptionsFixtures() {
    @Test
    fun `an envelope whose state event is no stream should be an undecodable entry, not a failed read`(): Unit =
        runBlocking {
            val partition = partitionOf("o-1")
            topics.publish(orderRecord("o-1", 1).copyWith(access = mapOf("tier" to "gold")))
            val entry = TopicEntries(ORDER_TOPIC, topics, PARTITIONS).tail(partition, null).first()
            UndecodableMessage.isUndecodable(entry).assert().isTrue()
            entry.stream.ref.assert().isEqualTo(order("o-1"))
        }

    @Test
    fun `such an envelope should park its aggregate, and the partition's next records should be handled`(): Unit =
        runBlocking {
            start("node-1")
            val partition = partitionOf("o-1")
            val next = generateSequence(2) { it + 1 }.map { "o-$it" }.first { partitionOf(it) == partition }
            topics.publish(orderRecord("o-1", 1).copyWith(access = mapOf("tier" to "gold")), orderRecord(next, 1))
            eventually { handled.versionsOf(next) == listOf(1) }
            eventually { ledger.parked(BOARD_ID, order("o-1")) != null }
            handled.versionsOf("o-1").assert().isEmpty()
        }
}
