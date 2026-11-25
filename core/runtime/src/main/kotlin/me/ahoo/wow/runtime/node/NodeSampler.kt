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
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.health.StoreActivity
import me.ahoo.wow.runtime.health.launchLoop
import me.ahoo.wow.spi.observation.ObservedEvent.Fatal.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One sampler of what a [node] reports that is read rather than heard, every [interval]: the head of each partition of
 * each log group the node has positions in, and how many entries each subscription has parked, by its group and name
 * ([ParkedSubscription]: each context has its own `snapshot`), counted up to [maxParked]. Both its health and its
 * gauges read the one sample ([latest]), so the ledgers are read once per interval, whoever reports them.
 *
 * Each [listen]er is told every sample, in order, a failing one isolated from the others. A read that fails or takes
 * longer than [readTimeout] (at most [interval]) is left out of its sample, named among what it could not read
 * ([NodeSample.unreadHeads], [NodeSample.unreadParked]), and the next one tries again, so a store that hangs delays its
 * readers by that much at most. A read logs a warning as it starts failing and a line as it reads again, debug for the
 * samples between, so a store down a while logs twice, not every sample. While the node is not running, a sample reads
 * nothing. [start] samples at once, then every [interval]; [close] stops it.
 */
@InternalWowApi
class NodeSampler(
    val node: ServiceNode,
    val interval: Duration = DEFAULT_INTERVAL,
    val maxParked: Int = DEFAULT_MAX_PARKED,
    readTimeout: Duration = DEFAULT_READ_TIMEOUT,
    /** Where it samples: by default with the node's handler, so its loop's end is the node's ([launchLoop]). */
    private val context: CoroutineContext = Dispatchers.Default + node.fatals.contextOf(Component.NODE, "sampler"),
    private val nanos: () -> Long = System::nanoTime,
) : AutoCloseable {
    init {
        require(interval.isPositive()) { "The sample interval is positive." }
        require(maxParked >= 1) { "At least one parked entry is counted." }
    }

    private val readTimeout: Duration = minOf(readTimeout, interval)

    private val listeners = CopyOnWriteArrayList<suspend (NodeSample) -> Unit>()

    /** The reads failing now, by what they read, each since when ([nanos]). */
    private val failing = ConcurrentHashMap<String, Long>()

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
        running = CoroutineScope(context + SupervisorJob()).launchLoop("sampler") {
            sample()
            delay(interval)
        }
    }

    /** Takes one sample, keeps it as [latest] and tells every listener of it. */
    suspend fun sample(): NodeSample {
        val made = HashSet<String>()
        val sample = if (node.state == ServiceNode.State.RUNNING) {
            val unreadHeads = HashSet<String>()
            val unreadParked = HashSet<ParkedSubscription>()
            val heads = heads(made, unreadHeads)
            val parked = parked(made, unreadParked)
            NodeSample(nanos(), heads, parked, latest, unreadHeads, unreadParked)
        } else {
            NodeSample(nanos(), emptyMap(), emptyMap(), latest, emptySet(), emptySet())
        }
        // A read the node no longer makes (a group handed off, a node stopped) is not failing any more.
        failing.keys.retainAll(made)
        latest = sample
        listeners.forEach { listener -> quietly("tell a listener of its sample") { listener(sample) } }
        return sample
    }

    /**
     * The head of each partition of each log group the node has positions in, by group and partition: one read of the
     * group's log for all its partitions ([me.ahoo.wow.spi.log.EventLog.heads]); a group it could not read in [unread].
     */
    private suspend fun heads(made: MutableSet<String>, unread: MutableSet<String>): Map<Pair<String, Int>, Long> {
        val heads = HashMap<Pair<String, Int>, Long>()
        node.contexts.values.flatMap { it.logGroups }.forEach { group ->
            val name = group.subscriptions.group
            val answered = read(made, "read the heads of $name") {
                val partitions = group.subscriptions.positions().map { it.partition }.distinct()
                if (partitions.isNotEmpty()) {
                    group.log.heads(partitions).forEach { (partition, head) ->
                        heads[name to partition] = head ?: 0L
                    }
                }
            }
            if (!answered) unread += name
        }
        return heads
    }

    /**
     * How many entries each subscription has parked, by its group and name, each up to [maxParked]; a subscription it
     * could not count in [unread].
     */
    private suspend fun parked(
        made: MutableSet<String>,
        unread: MutableSet<ParkedSubscription>,
    ): Map<ParkedSubscription, Int> {
        val parked = HashMap<ParkedSubscription, Int>()
        node.parked.groupedSubscriptions.forEach { subscription ->
            val counted = read(made, "count the parked entries of ${subscription.group}/${subscription.name}") {
                parked[subscription] = node.parked.count(subscription, maxParked)
            }
            if (!counted) unread += subscription
        }
        return parked
    }

    /**
     * [block], the sample's read to [action], recorded in [made]: whether it read, in [readTimeout]. One that fails or
     * takes longer is left out of the sample, and the next tries again ([failed]); one that reads after failing says
     * so, and since when it failed.
     */
    @Suppress("TooGenericExceptionCaught") // Left out of the sample; the next one tries again.
    private suspend fun read(made: MutableSet<String>, action: String, block: suspend () -> Unit): Boolean {
        made += action
        val answered = try {
            withTimeoutOrNull(readTimeout) { block() } != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed(action, failure)
            return false
        }
        if (!answered) {
            failed(action, null)
            return false
        }
        failing.remove(action)?.let { since ->
            logger.info { "Node ${node.node} can $action again, after ${(nanos() - since).nanoseconds}." }
        }
        return true
    }

    /**
     * The read to [action] failed with [failure], or took longer than [readTimeout] without one: a warning as it starts
     * failing, naming what its readers do meanwhile, debug while it fails again, so a store down a while logs twice
     * (this, and the line as it reads again), not every sample.
     */
    private fun failed(action: String, failure: Exception?) {
        val what = if (failure == null) "takes longer than $readTimeout to $action" else "fails to $action"
        if (failing.putIfAbsent(action, nanos()) != null) {
            logger.debug(failure) { "Node ${node.node} $what again; sampling again in $interval." }
            return
        }
        logger.warn(failure) {
            "Node ${node.node} $what, and tries again every $interval; until it can, the node's health leaves it " +
                "out and its gauges keep their last values."
        }
    }

    @Suppress("TooGenericExceptionCaught") // A listener that fails is told the next sample anyway.
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
 * partition, and the [parked] entries of each subscription, by its group and name; a read that failed is missing, and
 * named in [unreadHeads] or [unreadParked], so a reader tells a subject it could not read from one that is gone.
 * [previous] is the sample before it, for what grows.
 */
@InternalWowApi
class NodeSample internal constructor(
    val at: Long,
    val heads: Map<Pair<String, Int>, Long>,
    val parked: Map<ParkedSubscription, Int>,
    previous: NodeSample?,
    /** The log groups whose heads this sample could not read. */
    val unreadHeads: Set<String>,
    /** The subscriptions whose parked entries this sample could not count. */
    val unreadParked: Set<ParkedSubscription>,
) {
    /** The parked entries of the sample before this one, by subscription. */
    val previousParked: Map<ParkedSubscription, Int> = previous?.parked.orEmpty()

    @InternalWowApi
    companion object {
        /** No sample yet. */
        val EMPTY: NodeSample = NodeSample(StoreActivity.NONE, emptyMap(), emptyMap(), null, emptySet(), emptySet())
    }
}
