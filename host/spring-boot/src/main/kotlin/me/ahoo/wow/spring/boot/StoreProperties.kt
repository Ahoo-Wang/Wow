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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.runtime.access.AccessStampOptions
import me.ahoo.wow.runtime.log.HandoffOptions
import me.ahoo.wow.runtime.snapshot.SnapshotOptions
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.TailPolling
import me.ahoo.wow.spring.boot.WowProperties.Companion.DEFAULT_PARTITIONS
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

@InternalWowApi
class LogProperties {
    /**
     * The partitions of each context's log: fixed once the log holds entries, a power of two that divides 4096,
     * refused at start otherwise.
     */
    var partitions: Int = DEFAULT_PARTITIONS
        set(value) {
            field = Partitioner.requirePartitions(value)
        }

    /** How often the tail of a PostgreSQL or MongoDB log polls while it finds entries. */
    var pollInterval: Duration = TailPolling.DEFAULT.interval.toJavaDuration()

    /** The longest a tail waits between polls that find nothing: an idle tail backs off up to it. */
    var maxPollInterval: Duration = TailPolling.DEFAULT.maxInterval.toJavaDuration()

    /** The tails' polling these settings give. */
    internal fun tailPolling(): TailPolling =
        TailPolling(pollInterval.toKotlinDuration(), maxPollInterval.toKotlinDuration())

    /**
     * The hand-over of the node's own commits to its tails in memory, always on: a tail waits
     * [maxPollInterval] without a commit before it reads the store.
     */
    internal fun handoffOptions(): HandoffOptions =
        HandoffOptions(probeInterval = maxPollInterval.toKotlinDuration())

    /**
     * The store of every aggregate's log that `wow.aggregates.[<context>.<aggregate>].log.store` does not name:
     * one of `wow.stores`, the only one when not set.
     */
    var store: String? = null
}

/**
 * A named store instance (`wow.stores.<name>`): its [type], `memory`, `postgres`, `mongo` or a community backend's
 * (the type of its store provider), how it manages the framework's schema ([ddl]) and how its queries run
 * ([query]). Every other key of the store is a setting its provider declares, at the same level
 * (`wow.stores.<name>.data-source`, `wow.stores.<name>.client`): the host reserves [RESERVED] alone, and a key no
 * provider of the store's type declares fails the start, naming the ones it does. Two stores of one type may serve
 * different databases; two log stores may not share one.
 */
@InternalWowApi
class StoreProperties {
    /** The store's type: `memory`, `postgres`, `mongo`, `elasticsearch` or a community backend's. */
    var type: String? = null

    /** How this store manages the framework's schema, over `wow.ddl`. */
    var ddl: Ddl? = null

    /** How the store's snapshot and `events` queries run. */
    var query: StoreQueryProperties = StoreQueryProperties()

    internal companion object {
        /** The keys of a store the host reads itself: no provider declares an option of these names. */
        val RESERVED: Set<String> = setOf("type", "ddl", "query")
    }
}

/**
 * How one store's queries run (`wow.stores.<name>.query.*`): [maxConcurrent], how many run at once, over
 * `wow.query.max-concurrent` (beyond it a query is `wow.query.busy`, 429; `0` for no bound); [maxWait] and
 * [maxQueued], how long and how many queries beyond it wait for a permit, over `wow.query.max-wait` and
 * `wow.query.max-queued`; [events], its `events` queries; [adaptive], how its bound adapts to its write pressure,
 * over `wow.query.adaptive.*`.
 */
@InternalWowApi
class StoreQueryProperties {
    /** How many of the store's queries run at once, over `wow.query.max-concurrent`; `0` for no bound. */
    var maxConcurrent: Int? = null

    /** How long a query beyond the bound waits for a permit, over `wow.query.max-wait`. */
    var maxWait: Duration? = null

    /** How many queries beyond the bound wait for a permit, over `wow.query.max-queued`. */
    var maxQueued: Int? = null
    var events: QueryEventsProperties = QueryEventsProperties()
    var adaptive: StoreAdaptiveProperties = StoreAdaptiveProperties()
}

/**
 * How one store's query bound adapts to its write pressure (`wow.stores.<name>.query.adaptive.*`): [floor] and
 * [ceiling] over `wow.query.adaptive.*`.
 */
@InternalWowApi
class StoreAdaptiveProperties {
    /** The least the store's adaptive query bound goes down to, over `wow.query.adaptive.floor`. */
    var floor: Int? = null

    /** The most the store's adaptive query bound goes up to, over `wow.query.adaptive.ceiling`. */
    var ceiling: Int? = null
}

/**
 * The `events` queries of a store or an aggregate type: [unpinned], whether one that names no
 * aggregate runs, over the store's and `wow.query.events.unpinned`.
 */
@InternalWowApi
class QueryEventsProperties {
    /** Whether an `events` query that names no aggregate runs, over `wow.query.events.unpinned`. */
    var unpinned: UnpinnedEvents? = null
}

/**
 * Where the snapshots are, and how the snapshot maintenance runs. Snapshots may be written to several stores; a
 * store added to them is backfilled from the log: switch the primary to it once it caught up, then drop the old
 * one.
 */
@InternalWowApi
class SnapshotProperties {
    /**
     * The stores of `wow.stores` every aggregate's snapshots are written to, unless its
     * `wow.aggregates.[<context>.<aggregate>].snapshot.stores` names others; `wow.log.store` alone when empty. The
     * first one's built-in subscription is `wow.snapshot`, each other one's `wow.snapshot:<store>`.
     */
    var stores: MutableList<String> = ArrayList()

    /**
     * The one of the stores the write side and the state reads read, and the snapshot queries unless an aggregate's
     * `wow.aggregates.[<context>.<aggregate>].query.snapshot.store` names another; the first when not set.
     */
    var primary: String? = null

    /** How often the snapshot maintenance runs: state revision claims, in-place rebuilds and backfill checks. */
    var maintenanceInterval: Duration = SnapshotOptions.DEFAULT_INTERVAL.toJavaDuration()

    /** How an in-place rebuild rewrites the snapshots. */
    var rebuild: SnapshotRebuildProperties = SnapshotRebuildProperties()

    /** How many snapshots of a partition the snapshot subscriptions write together at most. */
    var writeBatch: Int = SnapshotOptions.DEFAULT_WRITE_BATCH

    /** The node's snapshot maintenance and write options. */
    fun options(): SnapshotOptions {
        val every = maintenanceInterval.toKotlinDuration()
        val ttl = maxOf(SnapshotOptions.DEFAULT_TTL, every * TTL_INTERVALS)
        return SnapshotOptions(every, rebuild.pageSize, rebuild.maxPages, ttl, ttl, writeBatch)
    }

    private companion object {
        /** A lease or membership outlives this many runs. */
        const val TTL_INTERVALS = 3
    }
}

/** How an in-place rebuild of the snapshots rewrites them (`wow.snapshot.rebuild.*`). */
@InternalWowApi
class SnapshotRebuildProperties {
    /** How many snapshots a rebuild rewrites per page. */
    var pageSize: Int = SnapshotOptions.DEFAULT_BATCH

    /** The most pages a rebuild rewrites in one maintenance run. */
    var maxPages: Int = SnapshotOptions.DEFAULT_PAGES
}

/** The in-memory provider's settings, `wow.memory.*`, as each provider has its group (`wow.kafka.*`, `wow.mongo.*`). */
@InternalWowApi
class MemoryProperties {
    /**
     * The messages each partition of the topics in the JVM keeps (`wow.transport` `memory`, or `auto` in
     * development without a transport), at least one: past it, a partition drops its oldest, as a broker's
     * retention does, and a subscription behind them reads on from the oldest kept, an offset gap. The topics stay
     * bounded however long the node runs.
     */
    var topicRetention: Int = InMemoryTopics.DEFAULT_RETENTION
        set(value) {
            require(value >= 1) { "wow.memory.topic-retention keeps at least one message: $value." }
            field = value
        }
}

/**
 * How the access attributes are stamped over the histories: by the built-in subscription `wow.access`, in stamps of a
 * bounded number of streams, remembering the last attributes of a bounded number of aggregates; and by the access
 * walk of each aggregate type, a page of aggregates at a time, at a bounded rate and a bounded share of the time.
 */
@InternalWowApi
class AccessProperties {
    /** The most streams one stamp writes: a long history is stamped in statements of at most this many. */
    var stampBatch: Int = AccessStampOptions.DEFAULT_BATCH

    /** The aggregates whose last stamped attributes the subscription remembers, sparing it a read of the stream before. */
    var capacity: Int = AccessStampOptions.DEFAULT_CAPACITY

    /**
     * The most aggregates a page of an access walk stamps; the walk records how far it is after each page, about every
     * five seconds at its pace.
     */
    var walkBatch: Int = AccessStampOptions.DEFAULT_WALK_BATCH

    /**
     * The most aggregates an access walk stamps a second, each one's whole history and its snapshot, however large
     * its share.
     */
    var walkRate: Int = AccessStampOptions.DEFAULT_WALK_RATE

    /**
     * The most of the time an access walk takes, as a share of one connection of the log store's, above 0 and at
     * most 1: after each aggregate it waits so its stamping took at most this share. While the store's writes press it
     * gives way, down to an eighth of it. Raise it in a maintenance window to walk faster.
     */
    var walkShare: Double = AccessStampOptions.DEFAULT_WALK_SHARE

    /** The node's stamping options. */
    fun options(): AccessStampOptions = AccessStampOptions(stampBatch, capacity, walkBatch, walkRate, walkShare)
}
