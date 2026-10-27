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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.cluster.ClusterOptions
import me.ahoo.wow.runtime.cluster.LeaseGroup
import me.ahoo.wow.runtime.cluster.PartitionOwnership
import me.ahoo.wow.runtime.cluster.ServiceMembership
import me.ahoo.wow.runtime.subscription.EntrySource
import me.ahoo.wow.runtime.subscription.FailureClassification
import me.ahoo.wow.runtime.subscription.ParkedNotifier
import me.ahoo.wow.runtime.subscription.PausedPartition
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionOptions
import me.ahoo.wow.runtime.subscription.SubscriptionPauses
import me.ahoo.wow.runtime.subscription.SubscriptionRuntime
import me.ahoo.wow.runtime.subscription.ThrottledPartition
import me.ahoo.wow.runtime.subscription.retryForever
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.transport.PartitionedSource
import java.time.Clock
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * The [subscriptions] of one node that read another context's published [topic]: the
 * runtime of a log's subscriptions over the topic instead, so they keep the semantics of the log's (a shared tail per
 * partition, lanes per aggregate, a checkpoint per subscription and partition at the contiguous low watermark, parking
 * in the ledger), with no Kafka consumer group:
 *
 * - **Assignment**: the topic's partitions are leased in the lease group `(service, "topic:<topic>")`
 *   ([LeaseGroup.topic]) and spread over the service's nodes by HRW, by the same [PartitionOwnership] as a context's
 *   log; the node reads the partitions it holds, each assigned to its consumer by hand.
 * - **Offsets**: each subscription's checkpoint per partition is in [checkpoints], under the lease group's name: the
 *   offset to read next, saved once every record before it is done with. Nothing is committed to Kafka.
 * - **One reader per (node, topic)**: one [SubscriptionRuntime] reads each held partition once and fans its records
 *   out to every subscription of the topic.
 * - **Parking**: an aggregate parked by one of them is recorded in [ledger] with the offset of its record; resuming it
 *   re-scans that partition from there for the records keyed by the aggregate ([TopicEntries]); an entry whose offset
 *   the topic no longer retains is marked unrecoverable and can only be [skip]ped.
 * - **Delivery** is at least once: a handover or a restart hands over again what is past the saved
 *   checkpoint, and a subscription drops a copy of a version it remembers handling (`StateEventRecord.id`).
 *
 * [start] returns at once: the partitions are read once the topic has some, which it may not have yet when its
 * publisher has not started, and the lease group is joined then ([awaitStarted]). [stop] hands the held partitions
 * off, saving the checkpoints, and leaves the group.
 *
 * **Partitions added** while it reads: every [partitionRefresh] it reads the topic's partitions
 * again, and when there are more, hands the held ones off, saving their checkpoints, and joins the group again over
 * all of them, so the new ones are read too, from their beginning. HRW gives each partition the same owner whatever
 * their number, so the partitions held before are held again. A topic's partitions are never fewer.
 */
@InternalWowApi
class TopicSubscriptions internal constructor(
    val service: String,
    val topic: String,
    val node: String,
    private val addressOf: () -> String,
    private val source: PartitionedSource,
    private val checkpoints: CheckpointStore,
    private val leases: LeaseStore,
    internal val ledger: FailureLedger,
    subscriptions: Collection<Subscription>,
    private val options: SubscriptionOptions,
    private val cluster: ClusterOptions,
    private val clock: Clock,
    private val context: CoroutineContext,
    private val signals: SignalSender,
    /** How the topic's partitions are read, given their number: as published streams, or commands, or receipts. */
    private val entries: (Int) -> EntrySource,
    /** Hears what the parked entries come to. */
    private val notifier: ParkedNotifier = ParkedNotifier.NONE,
    /** How often the topic's partitions are read again, for those added; never when infinite. */
    private val partitionRefresh: Duration = Duration.INFINITE,
    /** Sees each message its subscriptions consume. */
    private val observer: Observer = Observer.NONE,
    /** How its subscriptions classify their handlers' failures. */
    private val classification: FailureClassification = FailureClassification.DEFAULT,
    /** The node's membership in its service on [leases], shared with its other lease groups. */
    private val membership: ServiceMembership? = null,
) : SubscriptionPauses {
    @Suppress("LongParameterList") // The parts of the subscriptions, as the primary constructor takes them.
    constructor(
        service: String,
        topic: String,
        node: String,
        addressOf: () -> String,
        source: PartitionedSource,
        checkpoints: CheckpointStore,
        leases: LeaseStore,
        ledger: FailureLedger,
        subscriptions: Collection<Subscription>,
        options: SubscriptionOptions = SubscriptionOptions(),
        cluster: ClusterOptions = ClusterOptions(),
        clock: Clock = Clock.systemUTC(),
        context: CoroutineContext = Dispatchers.Default,
        signals: SignalSender = SignalSender.NONE,
        notifier: ParkedNotifier = ParkedNotifier.NONE,
        partitionRefresh: Duration = Duration.INFINITE,
        observer: Observer = Observer.NONE,
        classification: FailureClassification = FailureClassification.DEFAULT,
        membership: ServiceMembership? = null,
    ) : this(
        service,
        topic,
        node,
        addressOf,
        source,
        checkpoints,
        leases,
        ledger,
        subscriptions,
        options,
        cluster,
        clock,
        context,
        signals,
        { partitions -> TopicEntries(topic, source, partitions, observer) },
        notifier,
        partitionRefresh,
        observer,
        classification,
        membership,
    )

    /** The lease group of the topic's partitions: `(service, "topic:<topic>")`. */
    val group: LeaseGroup = LeaseGroup.topic(service, topic)

    val subscriptions: List<Subscription> = subscriptions.toList()

    private val lifecycle = Mutex()
    private var scope: CoroutineScope? = null
    private var joining: Job? = null
    private var started = CompletableDeferred<Running>()

    /** The partitions' runtime and their ownership, once the topic's [partitions] are known. */
    private class Running(val runtime: SubscriptionRuntime, val ownership: PartitionOwnership, val partitions: Int)

    @Volatile
    private var running: Running? = null

    @Volatile
    private var state: JoinState = JoinState.STOPPED

    /**
     * Where this node is in joining the topic's lease group, for a health model to report: a node
     * [JoinState.JOINING] for long reads nothing of the topic. See [joinFailures] for why it waits.
     */
    val joinState: JoinState get() = state

    /**
     * The attempts to join that failed since the node last joined: to read the topic's partitions (none yet counts)
     * or to join its lease group; zero once it reads them.
     */
    @Volatile
    var joinFailures: Int = 0
        private set

    /** Why the last attempt to join failed, while [joinFailures] is not zero. */
    @Volatile
    var lastJoinFailure: Exception? = null
        private set

    init {
        require(this.subscriptions.isNotEmpty()) { "Topic $topic has no subscription to read it." }
        this.subscriptions.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
            throw IllegalArgumentException("Topic $topic has two subscriptions named $it.")
        }
    }

    /** The runtime reading the partitions, once started. */
    val runtime: SubscriptionRuntime? get() = running?.runtime

    /** The partitions of the topic its subscriptions have paused on a systemic failure here. */
    override fun paused(): List<PausedPartition> = runtime?.paused().orEmpty()

    /** The partitions of the topic its subscriptions throttle on backpressure here. */
    fun throttled(): List<ThrottledPartition> = runtime?.throttled().orEmpty()

    /** This node's ownership of the topic's partitions, once started. */
    val ownership: PartitionOwnership? get() = running?.ownership

    /**
     * Joins the lease group in the background, as soon as the topic has partitions: reads them (retrying while it has
     * none), then acquires this node's share and reads it, retrying until it joins: a join that fails,
     * the lease store unreachable say, is undone and tried again after [SubscriptionOptions.readRetry]'s backoff, and
     * [joinState] stays [JoinState.JOINING] meanwhile.
     */
    suspend fun start(): Unit = lifecycle.withLock {
        check(scope == null) { "The subscriptions of $topic on $node are already started." }
        val root = CoroutineScope(context + SupervisorJob())
        scope = root
        started = CompletableDeferred()
        joinFailures = 0
        state = JoinState.JOINING
        val deferred = started
        joining = root.launch {
            val partitions = retryForever(options.readRetry, { "read the partitions of $topic" }, ::joinFailed) {
                source.partitions(topic).also {
                    check(it.isNotEmpty()) { "Topic $topic has no partitions yet." }
                    check(it == it.indices.toList()) { "The partitions of $topic are not numbered from 0: $it." }
                }
            }
            val run = join(partitions.size)
            deferred.complete(run)
            logger.info { "$node reads topic $topic (${partitions.size} partitions) for ${subscriptions.size} subscriptions." }
            if (partitionRefresh.isFinite()) {
                refresh()
            }
        }
    }

    /**
     * Joins the lease group over [partitions] partitions until it succeeds: each attempt that fails has undone itself
     * ([PartitionOwnership.start]), so the next one starts afresh.
     */
    private suspend fun join(partitions: Int): Running {
        state = JoinState.JOINING
        val run = retryForever(options.readRetry, { "join $group over $partitions partitions" }, ::joinFailed) {
            joinOver(partitions)
        }
        running = run
        joinFailures = 0
        lastJoinFailure = null
        state = JoinState.READING
        return run
    }

    private fun joinFailed(failure: Exception) {
        joinFailures++
        lastJoinFailure = failure
    }

    /** Joins the lease group over [partitions] partitions, reading this node's share of them. */
    private suspend fun joinOver(partitions: Int): Running {
        val runtime = SubscriptionRuntime(
            group.name,
            entries(partitions),
            checkpoints,
            ledger,
            subscriptions,
            options,
            clock,
            context,
            signals,
            notifier,
            observer,
            classification,
        )
        val ownership = PartitionOwnership(
            group,
            node,
            addressOf,
            partitions,
            leases,
            cluster,
            runtime,
            context,
            membership = membership,
        )
        ownership.start()
        return Running(runtime, ownership, partitions)
    }

    /**
     * Reads the topic's partitions every [partitionRefresh], and joins again over them when there are more: the held
     * ones are handed off, then the group joined over all of them, retrying as [start] does until it succeeds.
     */
    @Suppress("TooGenericExceptionCaught") // A refresh that fails is tried again at the next one.
    private suspend fun refresh() {
        while (true) {
            delay(partitionRefresh)
            val current = checkNotNull(running)
            val count = try {
                source.partitions(topic).size
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.warn(
                    failure
                ) { "$node failed to read the partitions of $topic; it tries again in $partitionRefresh." }
                continue
            }
            if (count > current.partitions) {
                // Not cancelled half-way: the held partitions are handed off with their checkpoints saved.
                state = JoinState.JOINING
                withContext(NonCancellable) {
                    current.ownership.stop()
                    running = null
                }
                join(count)
                logger.info { "$node reads topic $topic over $count partitions, ${current.partitions} before." }
            }
        }
    }

    /** Waits until the topic's partitions are known and the lease group joined. */
    suspend fun awaitStarted() {
        started.await()
    }

    /** Hands off the partitions held, saving every checkpoint, and leaves the group; stopping twice does nothing. */
    suspend fun stop(): Unit = lifecycle.withLock {
        val root = scope ?: return@withLock
        try {
            joining?.cancelAndJoin()
            running?.ownership?.stop()
        } finally {
            state = JoinState.STOPPED
            running = null
            joining = null
            scope = null
            root.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    /**
     * Handles [subscription]'s parked aggregate [ref] again by re-scanning its partition from the offset parked
     * ([SubscriptionRuntime.resume]); this node must hold the partition. False when it was not parked, failed again,
     * or is unrecoverable.
     */
    suspend fun resume(subscription: String, ref: AggregateRef): Boolean {
        val runtime = checkNotNull(runtime) { "The subscriptions of $topic on $node are not reading yet." }
        return runtime.resume(subscription, ref)
    }

    /** Resolves [subscription]'s parked aggregate [ref] without handling what was skipped. */
    suspend fun skip(subscription: String, ref: AggregateRef): Boolean {
        require(subscriptions.any { it.name == subscription }) { "No subscription $subscription reads $topic." }
        return runtime?.skip(subscription, ref) ?: (ledger.resolve(subscription, ref) != null)
    }

    override fun toString(): String = "TopicSubscriptions($topic, ${subscriptions.map { it.name }})"

    /** Where a node is in joining a topic's lease group ([joinState]). */
    @InternalWowApi
    enum class JoinState {
        /** Not started, or stopped. */
        STOPPED,

        /**
         * Started, but not reading the topic: waiting for its partitions, retrying a join that failed, or joining
         * again over partitions added.
         */
        JOINING,

        /** Joined: reading this node's share of the topic's partitions. */
        READING,
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
