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
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.Test

/**
 * A record of a state topic that holds no event, a commit whose events its model all retired, which the transport
 * sink publishes so that an aggregate's versions are contiguous on its topic: it stands for its version alone. No
 * handler is called, nothing parks, and the checkpoint passes it.
 */
class TopicRetiredOnlyTest : TopicSubscriptionsFixtures() {
    @Test
    fun `a record that holds no event should be passed over, no handler called and nothing parked`(): Unit =
        runBlocking {
            start("node-1")
            val partition = partitionOf("o-1")
            val retired = orderRecord("o-1", 2).copyWith(events = emptyList())
            topics.publish(orderRecord("o-1", 1), retired, orderRecord("o-1", 3))
            eventually { handled.versionsOf("o-1") == listOf(1, 3) }
            eventually { checkpoints.load(LeaseGroup.topic(SERVICE, ORDER_TOPIC).name, BOARD, partition) == 3L }
            ledger.parked(BOARD_ID, order("o-1")).assert().isNull()
            undecodable.assert().isEmpty()
        }
}
