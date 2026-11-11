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
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.subscription.Handled
import me.ahoo.wow.runtime.subscription.RecordingCheckpoints
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.SubscriptionStart
import me.ahoo.wow.runtime.subscription.testOptions
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.SubscriptionId
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * A topic of orders over [InMemoryTopics], its leases, checkpoints and ledger, the board subscription and the nodes
 * started on it: what the topic subscriptions' tests share, each test with its own.
 */
abstract class TopicSubscriptionsFixtures {
    protected val topics = InMemoryTopics(PARTITIONS).also { it.create(ORDER_TOPIC, PARTITIONS) }
    protected val leases = InMemoryLeaseStore()
    protected val checkpoints = RecordingCheckpoints()
    protected val ledger = InMemoryFailureLedger()
    protected val handled = Handled()
    protected val started = CopyOnWriteArrayList<TopicSubscriptions>()

    /** The undecodable messages the observer heard of: offset and whether it was parked. */
    protected val undecodable = CopyOnWriteArrayList<Pair<Long, Boolean>>()
    protected val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            when (event) {
                is ObservedEvent.Undecodable -> undecodable += event.offset to event.parked
                else -> Unit
            }
        }
    }

    /** While set, the handler fails for this aggregate at this version. */
    @Volatile
    protected var failing: Pair<String, Int>? = null

    protected val board = Subscription(BOARD, SubscriptionFilter(ORDER.context, setOf(ORDER.name))) { entry ->
        val failure = failing
        if (failure != null && entry.ref.id == failure.first && entry.version >= failure.second) {
            throw IllegalStateException("${entry.ref}@${entry.version} fails")
        }
        checkNotNull(entry.published) { "A record read from a topic carries it." }
        handled.record(entry)
    }

    @AfterEach
    fun stop(): Unit = runBlocking {
        started.forEach { it.stop() }
    }

    protected suspend fun start(
        node: String,
        subscription: Subscription = board,
        starts: Map<SubscriptionId, SubscriptionStart> = emptyMap(),
    ): TopicSubscriptions = TopicSubscriptions(
        service = SERVICE,
        topic = ORDER_TOPIC,
        node = node,
        addressOf = { "$node.internal:9090" },
        source = topics,
        checkpoints = checkpoints,
        leases = leases,
        ledger = ledger,
        subscriptions = listOf(subscription),
        options = testOptions().copyWith(starts = starts),
        cluster = fastOptions(),
        observer = observer,
    ).also {
        started += it
        it.start()
        it.awaitStarted()
    }

    protected suspend fun partitionOf(id: String): Int = topics.partitionOf(ORDER_TOPIC, order(id).toString())

    protected companion object {
        val BOARD_ID = SubscriptionId(ORDER.context, BOARD)
        const val SERVICE = "board-service"
        const val BOARD = "Board.onOrder"
        const val PARTITIONS = 4

        /** Long enough for a read that fails to have been retried several times. */
        val READ_RETRIES = 300.milliseconds

        /** An envelope a later version may read: of another format, which this version does not. */
        const val BAD = """{"format":2,"kind":"state-event","id":"sales.order/t/o-1@2","payload":{}}"""
    }
}
