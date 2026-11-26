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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.sink.TopicSink
import me.ahoo.wow.runtime.subscription.Handled
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.subscription.testOptions
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A state topic gains partitions while services run (#6783): the topic sink sends an aggregate's records to its
 * partition in the context's log, not the one its key's hash maps to among the topic's partitions, so none of its keys
 * moves to an added partition and an aggregate's records are handled in version order across the growth.
 */
class TopicGrowthOrderTest : TopicSubscriptionsFixtures() {
    @Test
    fun `an aggregate whose key moves to an added partition should still be handled in version order`(): Unit =
        runBlocking {
            val topic = "wow.sales.moving.state"
            topics.create(topic, 2)
            // A key the in-memory partitioner (as Kafka's default one) maps to another partition among four than two,
            // which a sink placing by the key's hash would move.
            val moving = generateSequence(0) { it + 1 }.map { "m-$it" }.first {
                val hash = order(it).toString().hashCode()
                Math.floorMod(hash, 2) != Math.floorMod(hash, PARTITIONS)
            }
            val sink = TopicSink(topics, topic, 2)
            val firstStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val seen = Handled()
            val slow = Subscription(BOARD, SubscriptionFilter(ORDER.context, setOf(ORDER.name))) { entry ->
                // The reader of the partition the key left is behind: its handling of v1 is still running.
                if (entry.ref.id == moving && entry.version == 1) {
                    firstStarted.complete(Unit)
                    release.await()
                }
                seen.record(entry)
            }
            val node = TopicSubscriptions(
                service = SERVICE,
                topic = topic,
                node = "node-1",
                addressOf = { "node-1.internal:9090" },
                source = topics,
                checkpoints = checkpoints,
                leases = leases,
                ledger = ledger,
                subscriptions = listOf(slow),
                options = testOptions(),
                cluster = fastOptions(),
                partitionRefreshInterval = 20.milliseconds,
            ).also {
                started += it
                it.start()
                it.awaitStarted()
            }
            sink.write(listOf(orderRecord(moving, 1)))
            firstStarted.await()
            topics.grow(topic, PARTITIONS)
            eventually { node.ownership?.owned == (0 until PARTITIONS).toSet() }
            // To its partition in the log, among two, which the growth did not change.
            sink.write(listOf(orderRecord(moving, 2)))
            // Long enough for the added partition's worker to handle v2 when nothing holds it back.
            withTimeoutOrNull(1.seconds) {
                while (seen.versionsOf(moving).isEmpty()) {
                    delay(10.milliseconds)
                }
            }
            release.complete(Unit)
            eventually { seen.versionsOf(moving).size == 2 }
            seen.versionsOf(moving).assert().containsExactly(1, 2)
        }
}
