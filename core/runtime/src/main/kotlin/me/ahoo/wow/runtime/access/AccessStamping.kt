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

package me.ahoo.wow.runtime.access

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.runtime.snapshot.LruMap
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.AccessStamped.Cause
import me.ahoo.wow.spi.observation.Observer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How a context stamps the histories ([AccessStamping], [AccessWalking]): at most [batch] streams in one stamp, so one
 * long history is written in statements of a bounded size, each by the version key; the last stamped attributes of at
 * most [capacity] aggregates, which spare the follower a read of the previous stream; and the walks, [walkBatch]
 * aggregates a page at most, at most [walkRate] a second and at most [walkShare] of the time, run every [interval]
 * under a lease of [leaseTtl], each node a member of its access schema of each type for [memberTtl] after its last run.
 * The host configures them (`wow.access.*`).
 *
 * Fields may be added: Wow builds it from the host's configuration, so the constructor is internal to Wow.
 */
class AccessStampOptions @InternalWowApi constructor(
    val batch: Int = DEFAULT_BATCH,
    val capacity: Int = DEFAULT_CAPACITY,
    val walkBatch: Int = DEFAULT_WALK_BATCH,
    val walkRate: Int = DEFAULT_WALK_RATE,
    /**
     * The most of the time a walk takes, as a share of one connection of the log store's: after an aggregate whose
     * stamping took `t`, the walk waits `t * (1 - walkShare) / walkShare`. While the store's writes are pressed it
     * halves, down to an eighth of it, and comes back once they are not.
     */
    val walkShare: Double = DEFAULT_WALK_SHARE,
    val interval: Duration = DEFAULT_INTERVAL,
    val leaseTtl: Duration = DEFAULT_TTL,
    val memberTtl: Duration = DEFAULT_TTL,
) {
    init {
        require(batch >= 1) { "A stamp writes at least one stream." }
        require(capacity >= 1) { "The stamping remembers at least one aggregate." }
        require(walkBatch >= 1) { "A walk stamps at least one aggregate a page." }
        require(walkRate >= 1) { "A walk stamps at least one aggregate a second." }
        require(walkShare > 0.0 && walkShare <= 1.0) { "A walk takes a share of the time above 0 and at most 1." }
        require(interval.isPositive()) { "The walks run at a positive interval." }
        require(leaseTtl > interval) { "The walk lease outlives the interval between runs." }
        require(memberTtl > interval) { "A schema membership outlives the interval between runs." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is AccessStampOptions && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "AccessStampOptions(batch=$batch, capacity=$capacity, walkBatch=$walkBatch, walkRate=$walkRate, " +
            "walkShare=$walkShare, interval=$interval, leaseTtl=$leaseTtl, memberTtl=$memberTtl)"

    private fun valueFields(): List<Any> =
        listOf(batch, capacity, walkBatch, walkRate, walkShare, interval, leaseTtl, memberTtl)

    companion object {
        /** The most streams one stamp writes by default. */
        const val DEFAULT_BATCH: Int = 1_000

        /** The aggregates whose last attributes the stamping remembers by default. */
        const val DEFAULT_CAPACITY: Int = 100_000

        /** The aggregates a page of a walk stamps by default. */
        const val DEFAULT_WALK_BATCH: Int = 100

        /** The most aggregates a walk stamps a second by default. */
        const val DEFAULT_WALK_RATE: Int = 500

        /**
         * The most of the time a walk takes by default: the largest measured share whose walk left the commands' p99
         * latency near a run without a walk, on MongoDB, whose commands an unbounded walk does slow down.
         */
        const val DEFAULT_WALK_SHARE: Double = 0.25

        val DEFAULT_INTERVAL: Duration = 5.seconds
        val DEFAULT_TTL: Duration = 30.seconds
    }
}

/**
 * The built-in subscription [NAME] of one bounded context: it keeps the access attributes of each aggregate's history
 * its current ones. The kernel stamps each commit with the attributes after it ([AccessValues.ofStream]); when a
 * commit changed them (its attributes differ from those of the stream before it), this subscription stamps them over
 * every earlier stream of the aggregate ([AccessStamps.stamp]), as of the commit's version, in stamps of at most
 * [AccessStampOptions.batch] streams. So an events query authorizes an aggregate's whole history by what it is now,
 * within the subscription's lag.
 *
 * - **Every type**: the built-in owner and space are among the attributes, and any aggregate's scope can be
 *   transferred, so it follows every type of the context, from the beginning of the log.
 * - **The stream before**: the attributes this subscription last saw of the aggregate, when that was the stream right
 *   before ([AccessStampOptions.capacity] aggregates remembered), else the stream before as the log holds it. In either
 *   case it holds the attributes as of its version or later ones a stamp gave it, so a commit that changed nothing
 *   stamps nothing.
 * - **Idempotent**: a stamp writes only the streams not stamped as of a later version, so a replay, a retry or two
 *   owners of a partition at once leave each stream with its aggregate's latest attributes. A stream before that
 *   carries none (appended by a node of an earlier version, or imported) is stamped with the rest.
 * - **Whatever the schema**: the attributes are compared as stored, so a commit of a node of another access schema
 *   (a rolling deployment) is followed as any: history follows the aggregate's latest stream, and the walk of the new
 *   schema ([AccessWalking]) derives them anew once its claim moves.
 * - **A stream without attributes**: one not stamped is the walk's when the walk's heads reach it ([walking]'s
 *   [AccessWalking.covers]); after them (a node of an earlier version still running, an import), the follower stamps
 *   its aggregate's whole history with the attributes of its current state ([AccessWalking.stampCurrent]), once, and
 *   passes over its streams up to that version.
 * - **Run as [FunctionKind.PROJECT]**: it writes derived data of the log, as a projection does. A kind of its own would
 *   be a new value of an enum the wire carries (the OpenAPI document, the TypeScript client) for one built-in.
 */
@InternalWowApi
class AccessStamping(
    context: String,
    models: Collection<AggregateModel<*>>,
    private val kernel: Kernel,
    /** Where each type's history is stamped: its log; none, for a type whose log keeps no stamps. */
    private val stampsOf: (AggregateType) -> AccessStamps?,
    private val options: AccessStampOptions = AccessStampOptions(),
    /** The context's walks, which stamp the streams without attributes; none: such a stream stays as it is. */
    val walking: AccessWalking? = null,
    private val observer: Observer = Observer.NONE,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    private val models: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }
    private val last = LruMap<AggregateRef, Stamped>(options.capacity)

    init {
        require(this.models.keys.all { it.context == context }) { "Every stamped aggregate type is of $context." }
    }

    /** The subscription to register: of every aggregate type of the context, from the beginning of the log. */
    val subscription: Subscription = Subscription.builtIn(
        NAME,
        SubscriptionFilter(context, this.models.keys.mapTo(LinkedHashSet()) { it.name }),
        FunctionKind.PROJECT,
        ::stamp,
    )

    private suspend fun stamp(entry: SubscribedEntry) {
        val stream = entry.stream
        val ref = stream.ref
        val model = models[ref.type] ?: return
        val stamps = stampsOf(ref.type) ?: return
        val version = stream.version
        val access = stream.access
        if (access == null) {
            val caughtUp = last[ref]?.let { it.version >= version } == true
            if (!caughtUp && walking?.covers(ref.type, entry.position) != true) {
                // Its whole history, the streams after it that are already in the log too.
                walking?.stampCurrent(model, ref, Cause.CATCH_UP)?.let { last[ref] = it }
            }
            return
        }
        if (version > 1) {
            val before = last[ref]?.takeIf { it.version == version - 1 }?.access
                ?: kernel.streamAt(ref, version - 1)?.access
            if (before != access) {
                val started = time.markNow()
                val written = stamps.stampIn(ref, access, version, 1, version, options.batch)
                val millis = started.elapsedNow().inWholeMilliseconds
                observer.observed(ObservedEvent.AccessStamped(ref.type, Cause.CHANGE, written, millis))
                logger.debug { "Stamped the access attributes of $ref@$version over $written streams before it." }
            }
        }
        last[ref] = Stamped(version, access)
    }

    override fun toString(): String = "AccessStamping($NAME)"

    @InternalWowApi
    companion object {
        /** The name of the subscription, `wow.access`. */
        const val NAME: String = "wow.access"

        private val logger = KotlinLogging.logger {}
    }
}
