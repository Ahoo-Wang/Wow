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

package me.ahoo.wow.query.store

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi
import me.ahoo.wow.query.backend.QueryBindings
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StorePool

/**
 * The query side of a [Store]: a facet of the store ([Store.facet]) whose backend serves queries, giving the snapshot
 * queries over the snapshots it holds and the `events` queries over the logs it holds, each aggregate type's routed to
 * the store its snapshots or log are on. A store without it, or whose facet gives null, is not queried: its snapshot
 * and `events` routes are not served.
 *
 * How many of the store's queries run at once, when its configuration names no limit, follows from its pool
 * ([StoreQueryBounds], [me.ahoo.wow.spi.store.StorePool]).
 */
@WowSpi
interface StoreQueries {
    /** The snapshot queries of the store, opened with [options], or null when its snapshots are not queried. */
    fun snapshots(options: StoreQueryOptions): SnapshotQueries? = null

    /** The `events` queries of the store's logs, opened with [options], or null when they are not queried. */
    fun events(options: StoreQueryOptions): QueryBindings<AggregateType>? = null
}

/**
 * How many queries of a store run at once when its configuration names no limit (`wow.stores.<name>.query.
 * max-concurrent`, `wow.query.max-concurrent`), and how high the adaptive limit may rise when it names no ceiling
 * (`wow.stores.<name>.query.adaptive.ceiling`, `wow.query.adaptive.ceiling`), from the store's [StorePool]: the
 * snapshot and `events` queries of every aggregate type on it share the bound, and one beyond it is `wow.query.busy`.
 *
 * - A pool of the queries' own: all of it, for both.
 * - A pool the appends share: a quarter of it, at least [MIN_SHARED], so the queries never hold most of the
 *   connections the commands wait for; the ceiling half of it, never below the bound.
 * - A store whose pool does not say its size: [DEFAULT_MAX_CONCURRENT], for both, so the limit only falls below its
 *   start and climbs back.
 */
@InternalWowApi
object StoreQueryBounds {
    /** The bound of a store's concurrent queries when neither its configuration nor its pool gives one. */
    const val DEFAULT_MAX_CONCURRENT: Int = 16

    /** The fewest queries a store whose queries share the appends' pool runs at once. */
    const val MIN_SHARED: Int = 2

    /** The share of the appends' pool the queries take: one connection in four. */
    const val SHARED_FRACTION: Int = 4

    /** The share of the appends' pool the adaptive limit rises to at most: one connection in two. */
    const val SHARED_CEILING_FRACTION: Int = 2

    /** The bound of the queries of a store whose pool is [pool]. */
    @JvmStatic
    fun maxConcurrent(pool: StorePool?): Int {
        val size = pool?.queryConnections ?: return DEFAULT_MAX_CONCURRENT
        return if (pool.sharedWithAppends) maxOf(MIN_SHARED, size / SHARED_FRACTION) else size
    }

    /** The ceiling of the adaptive limit of the queries of a store whose pool is [pool]. */
    @JvmStatic
    fun ceiling(pool: StorePool?): Int {
        val size = pool?.queryConnections ?: return maxConcurrent(pool)
        return if (pool.sharedWithAppends) maxOf(maxConcurrent(pool), size / SHARED_CEILING_FRACTION) else size
    }
}

/**
 * The snapshot queries of one store: the [binding] of each aggregate type's backend and storage facts and, for a
 * backend that manages the indexes an aggregate's `x-wow-index` declares, how it [prepare]s the
 * aggregate's storage and [manageIndexes] them at startup.
 */
@WowSpi
interface SnapshotQueries : QueryBindings<AggregateType> {
    /** Whether it manages the indexes ([manageIndexes]). */
    val managesIndexes: Boolean get() = false

    /** Makes sure [type]'s storage exists, so that its facts are the storage's own (a table created on first write). */
    fun prepare(type: AggregateType) = Unit

    /**
     * Manages [plan], [type]'s indexes as the core planned them on its query model, as [StoreQueryOptions.indexDdl]
     * says: creates the missing ones under `auto`, reports them under `validate`.
     */
    fun manageIndexes(type: AggregateType, plan: QueryIndexPlan): QueryIndexReport = QueryIndexReport.NONE
}

/** What managing one aggregate's indexes did: the indexes it [created], and those it found [missing] or [invalid]. */
@WowSpi
class QueryIndexReport(
    val created: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val invalid: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is QueryIndexReport && created == other.created && missing == other.missing && invalid == other.invalid

    override fun hashCode(): Int = listOf(created, missing, invalid).hashCode()

    override fun toString(): String = "QueryIndexReport(created=$created, missing=$missing, invalid=$invalid)"

    companion object {
        /** Nothing managed. */
        @JvmField
        val NONE: QueryIndexReport = QueryIndexReport()
    }
}

/**
 * The query settings a store's queries are opened with: [indexDdl], how the indexes the Manifest declares are managed,
 * the `ddl` of the store (`wow.stores.<name>.ddl`, else `wow.ddl`); and [eventsReadPreference] and
 * [snapshotsReadPreference], the replica a backend with replicas reads the `events` and the snapshot queries from
 * (MongoDB's `secondaryPreferred`, say; `wow.stores.<name>.options.query-read-preference`, else
 * `wow.mongo.query-read-preference`), null for its primary. A snapshot read from a secondary may be behind the snapshot
 * a command's default wait waited for.
 *
 * Fields may be added: the host builds it, so the constructor is internal to Wow; a provider's test
 * builds one with [of].
 */
@WowSpi
class StoreQueryOptions @InternalWowApi constructor(
    val indexDdl: Ddl = Ddl.AUTO,
    val eventsReadPreference: String? = null,
    val snapshotsReadPreference: String? = null,
) {
    override fun toString(): String =
        "StoreQueryOptions(indexDdl=$indexDdl, eventsReadPreference=$eventsReadPreference, " +
            "snapshotsReadPreference=$snapshotsReadPreference)"

    companion object {
        /** The defaults: indexes `auto`, `events` read from the primary. */
        @JvmField
        val DEFAULT: StoreQueryOptions = StoreQueryOptions()

        /** Options of the given fields; parameters added later come last, with a default. */
        @JvmStatic
        @JvmOverloads
        fun of(indexDdl: Ddl = Ddl.AUTO, eventsReadPreference: String? = null): StoreQueryOptions =
            StoreQueryOptions(indexDdl, eventsReadPreference)

        /** Options of the given fields, the snapshot queries' read preference among them. */
        @JvmStatic
        fun of(
            indexDdl: Ddl,
            eventsReadPreference: String?,
            snapshotsReadPreference: String?,
        ): StoreQueryOptions = StoreQueryOptions(indexDdl, eventsReadPreference, snapshotsReadPreference)
    }
}
