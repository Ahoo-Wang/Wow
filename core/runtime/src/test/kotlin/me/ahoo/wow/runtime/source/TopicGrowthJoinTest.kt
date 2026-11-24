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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.StartFrom
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.subscription.Handled
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.transport.EnvelopeCodec
import me.ahoo.wow.spi.transport.TopicMessage
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Deep review r1 (runtime source): a topic that gains partitions is read again over all of them, "the new ones ...
 * from their beginning" (TopicSubscriptions' KDoc). The runtime built for the grown topic counts every subscription
 * with a checkpoint as joined (SubscriptionRuntime.start: `run.joined`), so a JOIN subscription (the default of a saga's
 * or a reactor's handler of another context's stream) starts each added partition where it ends when it first reads
 * it (WorkerStarts.startOf, SubscriptionStart.of(JOIN, joined = true) = End, TopicEntries.checkpointOf(End) =
 * offsets.end): a record the publisher wrote there before this node started the partition is never handled.
 */
class TopicGrowthJoinTest : TopicSubscriptionsFixtures() {
    private val joined = Handled()

    private val joining = Subscription(JOINING, SubscriptionFilter(ORDER.context, setOf(ORDER.name)), StartFrom.JOIN) {
        joined.record(it)
    }

    @Test
    fun `a record written to an added partition before the node reads it should reach a JOIN subscription`(): Unit =
        runBlocking {
            val topic = "wow.sales.joining.state"
            topics.create(topic, 2)
            val growing = TopicSubscriptions(
                service = SERVICE,
                topic = topic,
                node = "node-1",
                addressOf = { "node-1.internal:9090" },
                source = topics,
                checkpoints = checkpoints,
                leases = leases,
                ledger = ledger,
                subscriptions = listOf(board, joining),
                options = testOptions(),
                cluster = fastOptions(),
                partitionRefresh = 200.milliseconds,
            ).also {
                started += it
                it.start()
                it.awaitStarted()
            }
            // Read from the beginning by both: no subscription had read the topic before.
            topics.publish(orderRecord("o-1", 1), orderRecord("o-2", 1), topic = topic)
            eventually { handled.size == 2 && joined.size == 2 }
            // The publisher writes to an added partition before this node's refresh reads it.
            topics.grow(topic, PARTITIONS)
            val record = EnvelopeCodec.message(orderRecord("o-3", 1))
            topics.send(topic, TopicMessage.of(record.key, record.value, record.headers, PARTITIONS - 1))
            eventually { growing.ownership?.owned == (0 until PARTITIONS).toSet() }
            // The BEGINNING subscription reads it from the partition's beginning.
            eventually { handled.versionsOf("o-3") == listOf(1) }
            val reached = withTimeoutOrNull(2.seconds) {
                while (joined.versionsOf("o-3").isEmpty()) delay(10)
                true
            }
            reached.assert()
                .describedAs(
                    "o-3 handled by the JOIN subscription; its checkpoint in partition ${PARTITIONS - 1} is " +
                        "${checkpoints.load(growing.group.name, JOINING, PARTITIONS - 1)} (the topic's end when it first read " +
                        "it)",
                )
                .isNotNull()
        }

    private companion object {
        const val JOINING = "Saga.onOrder"
    }
}
