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

package me.ahoo.wow.runtime.node

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.health.StoreActivity
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One sampler of what a [node] reports that is read rather than heard, every [interval]: the head of each partition of
 * each log group the node has positions in, and how many entries each subscription has parked, by its group and name
 * ([ParkedSubscription]: each context has its own `snapshot`), counted up to [maxParked]. Both its health and its
 * gauges read the one sample ([latest]), so the ledgers are read once per interval, whoever reports them.
 *
 * Each [listen]er is told every sample, in order, a failing one isolated from the others. A read that fails or takes
 * longer than [readTimeout] (at most [interval]) is left out of its sample, and the next one tries again, so a store
 * that hangs delays its readers by that much at most. While the node is not running, a
 * sample reads nothing. [start] samples at once, then every [interval]; [close] stops it.
 */
@InternalWowApi
class NodeSampler(
    val node: ServiceNode,
    val interval: Duration = DEFAULT_INTERVAL,
    val maxParked: Int = DEFAULT_MAX_PARKED,
    readTimeout: Duration = DEFAULT_READ_TIMEOUT,
    private val context: CoroutineContext = Dispatchers.Default,
    private val nanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    init {
        require(interval.isPositive()) { "The sample interval is positive." }
        require(maxParked >= 1) { "At least one parked entry is counted." }
    }

    private val readTimeout: Duration = minOf(readTimeout, interval)

    private val listeners = CopyOnWriteArrayList<suspend (NodeSample) -> Unit>()

    @Volatile
    private var running: Job? = null

    /** The last sample taken, [NodeSample.EMPTY] before the first. */
    @Volatile
    var latest: NodeSample = NodeSample.EMPTY
        private set

    /** Tells [listener] of every sample from now on. */
    fun listen(listener: suspend (NodeSample) -> Unit) {
        listeners += listener
    }

    /** Samples at once, then every [interval]; starting twice does nothing. */
    @Synchronized
    fun start() {
        if (running != null) {
            return
        }
        running = CoroutineScope(context + SupervisorJob()).launch {
            while (true) {
                sample()
                delay(interval)
            }
        }
    }

    /** Takes one sample, keeps it as [latest] and tells every listener of it. */
    suspend fun sample(): NodeSample {
        val sample = if (node.state == ServiceNode.State.RUNNING) {
            NodeSample(nanos(), heads(), parked(), latest)
        } else {
            NodeSample(nanos(), emptyMap(), emptyMap(), latest)
        }
        latest = sample
        listeners.forEach { listener -> quietly("tell a listener of its sample") { listener(sample) } }
        return sample
    }

    /** The head of each partition of each log group the node has positions in, by group and partition. */
    private suspend fun heads(): Map<Pair<String, Int>, Long> {
        val heads = HashMap<Pair<String, Int>, Long>()
        node.contexts.values.flatMap { it.logGroups }.forEach { group ->
            val name = group.subscriptions.group
            quietly("read the heads of $name") {
                withTimeoutOrNull(readTimeout) {
                    group.subscriptions.positions().map { it.partition }.distinct().forEach { partition ->
                        heads[name to partition] = group.log.head(partition) ?: 0L
                    }
                }
            }
        }
        return heads
    }

    /** How many entries each subscription has parked, by its group and name, each up to [maxParked]. */
    private suspend fun parked(): Map<ParkedSubscription, Int> {
        val parked = HashMap<ParkedSubscription, Int>()
        node.parked.groupedSubscriptions.forEach { subscription ->
            quietly("count the parked entries of ${subscription.group}/${subscription.name}") {
                withTimeoutOrNull(readTimeout) { node.parked.count(subscription, maxParked) }?.let {
                    parked[subscription] = it
                }
            }
        }
        return parked
    }

    @Suppress("TooGenericExceptionCaught") // Left out of the sample; the next one tries again.
    private suspend fun quietly(action: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.debug(failure) { "Node ${node.node} failed to $action; sampling again in $interval." }
        }
    }

    /** Stops sampling. */
    @Synchronized
    override fun close() {
        running?.cancel()
        running = null
    }

    @InternalWowApi
    companion object {
        val DEFAULT_INTERVAL: Duration = 10.seconds
        const val DEFAULT_MAX_PARKED: Int = 10_000
        val DEFAULT_READ_TIMEOUT: Duration = 5.seconds
        private val logger = KotlinLogging.logger {}
    }
}

/**
 * One sample of a node, taken [at] (`System.nanoTime()`): the [heads] of the partitions of its log groups, by group and
 * partition, and the [parked] entries of each subscription, by its group and name; a read that failed is missing.
 * [previous] is the sample before it, for what grows.
 */
@InternalWowApi
class NodeSample internal constructor(
    val at: Long,
    val heads: Map<Pair<String, Int>, Long>,
    val parked: Map<ParkedSubscription, Int>,
    previous: NodeSample?,
) {
    /** The parked entries of the sample before this one, by subscription. */
    val previousParked: Map<ParkedSubscription, Int> = previous?.parked.orEmpty()

    @InternalWowApi
    companion object {
        /** No sample yet. */
        val EMPTY: NodeSample = NodeSample(StoreActivity.NONE, emptyMap(), emptyMap(), null)
    }
}
