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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.query.admission.AdaptiveLimitOptions
import me.ahoo.wow.query.admission.AdaptiveQueryLimit
import me.ahoo.wow.query.admission.QueryBulkhead
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StorePool
import me.ahoo.wow.spi.store.WritePressure
import me.ahoo.wow.spi.store.facet
import kotlin.time.Duration

/**
 * How the queries of a node's stores run, composed once, in `wow-query`, so every
 * host gets the same bounds: the [bulkheads] of the stores, by name, their adaptive [limits], the
 * bulkhead every dataset query runs under ([datasets]), and whether each aggregate type's events
 * queries that name no aggregate run ([unpinned]). A state read's replay of a log runs under the bulkhead of the log's
 * store too ([replay]).
 */
@InternalWowApi
class QueryStores(
    val bulkheads: Map<String, QueryBulkhead> = emptyMap(),
    /** The bounds of [bulkheads] that adapt to their store's write pressure. */
    val limits: List<AdaptiveQueryLimit> = emptyList(),
    /**
     * The bulkhead of the dataset queries (`wow.query.dataset.max-concurrent`), or null when they run unbounded. Its
     * limit is fixed: the warehouse's writes (the ClickHouse Sink's) give the node no write pressure to adapt to.
     */
    val datasets: QueryBulkhead? = null,
    private val unpinned: (AggregateType) -> UnpinnedEvents = { UnpinnedEvents.ALLOW },
) {
    /** Every bulkhead: the stores' and the datasets'. */
    val all: List<QueryBulkhead> get() = bulkheads.values + listOfNotNull(datasets)

    /** Whether [type]'s events queries that name no aggregate run. */
    fun unpinned(type: AggregateType): UnpinnedEvents = unpinned.invoke(type)

    /** Runs [read], a replay of a log on [store], under that store's bulkhead; at once without one. */
    suspend fun <T> replay(store: String?, read: suspend () -> T): T {
        val bulkhead = store?.let(bulkheads::get) ?: return read()
        return bulkhead.run(read)
    }

    /**
     * Whether the writes of [store] pressed in the last period its adaptive bound read ([AdaptiveQueryLimit.pressed]);
     * false for a store whose bound does not adapt, or none.
     */
    fun pressed(store: String?): Boolean = store != null && limits.any { it.bulkhead.store == store && it.pressed }

    override fun toString(): String = "QueryStores(${all.joinToString()})"

    @InternalWowApi
    companion object {
        /** The name the dataset queries' bulkhead goes by, in the health details and the meters' `store` tag. */
        const val DATASETS: String = "datasets"

        private val logger = KotlinLogging.logger {}

        /**
         * The bounds of [stores]' queries as [settings] say. The bulkhead of a store is
         * `wow.stores.<name>.query.max-concurrent`, else `wow.query.max-concurrent`, else its pool's
         * ([StoreQueryBounds.maxConcurrent]); `0` sets none. A store without [StoreQueries] has no queries to bound. A query beyond the bound waits at most the store's
         * `max-wait`, else the global one, in a queue of at most its `max-queued`, else the global one, else 4 per
         * permit.
         *
         * A bound neither setting fixes always adapts to the store's write pressure ([pressures]): it
         * starts at the pool's and moves between the floor and the ceiling (store over global, the ceiling else the
         * pool's [StoreQueryBounds.ceiling]). A bound below zero, or a ceiling below one, is refused
         * with the configuration key at fault.
         */
        @JvmStatic
        fun of(
            settings: QueryStoreSettings,
            stores: Map<String, Store>,
            pressures: (String) -> WritePressure = { WritePressure.NONE },
            unpinned: (AggregateType) -> UnpinnedEvents = { UnpinnedEvents.ALLOW },
        ): QueryStores {
            val limits = ArrayList<AdaptiveQueryLimit>()
            val bulkheads = stores.mapNotNull { (name, store) ->
                store.facet<StoreQueries>() ?: return@mapNotNull null
                val (bulkhead, adaptive) = bulkheadOf(name, store.facet<StorePool>(), settings, settings.stores[name])
                    ?: return@mapNotNull null
                adaptive?.let { limits += AdaptiveQueryLimit(bulkhead, pressures(name), it) }
                name to bulkhead
            }.toMap()
            if (bulkheads.isNotEmpty()) {
                logger.info { "The stores' queries run under ${bulkheads.values}." }
            }
            if (limits.isNotEmpty()) {
                logger.info { "The query bounds of ${limits.map { it.bulkhead.store }} adapt to their writes." }
            }
            val datasetBound = settings.datasets.maxConcurrent
            configCheck(
                datasetBound >= 0,
                "wow.query.dataset.max-concurrent",
                datasetBound,
                "Set 0 for no bound, or more."
            ) {
                "wow.query.dataset.max-concurrent is $datasetBound, below 0."
            }
            val datasets = settings.datasets.takeIf { it.maxConcurrent > 0 }?.let { dataset ->
                QueryBulkhead(
                    store = DATASETS,
                    maxConcurrent = dataset.maxConcurrent,
                    maxWait = dataset.maxWait ?: settings.maxWait,
                    maxQueued = dataset.maxQueued ?: settings.maxQueued
                        ?: (dataset.maxConcurrent * QueryBulkhead.DEFAULT_QUEUE_FACTOR),
                )
            }
            return QueryStores(bulkheads, limits, datasets, unpinned)
        }

        /** The bulkhead of the store [name], and its adaptive options when its bound adapts; null for no bound. */
        private fun bulkheadOf(
            name: String,
            pool: StorePool?,
            global: QueryStoreSettings,
            own: QueryStoreLimits?,
        ): Pair<QueryBulkhead, AdaptiveLimitOptions?>? {
            val fixed = own?.maxConcurrent ?: global.maxConcurrent
            val derived = fixed ?: StoreQueryBounds.maxConcurrent(pool)
            val concurrentKey = keyOf(name, own?.maxConcurrent != null, "max-concurrent")
            configCheck(derived >= 0, concurrentKey, derived, "Set 0 for no bound, or more.") {
                "$concurrentKey is $derived."
            }
            if (derived == 0) return null
            // A bound no setting fixes adapts to the store's write pressure; a fixed one does not.
            val adaptive = fixed == null
            val ceiling = if (adaptive) ceilingOf(name, pool, global, own) else derived
            val max = minOf(derived, ceiling)
            val wait = own?.maxWait ?: global.maxWait
            val waitKey = keyOf(name, own?.maxWait != null, "max-wait")
            configCheck(!wait.isNegative(), waitKey, wait, "Set 0 for no wait, or more.") {
                "A query's wait for a permit is never negative."
            }
            val queued = own?.maxQueued ?: global.maxQueued ?: (max * QueryBulkhead.DEFAULT_QUEUE_FACTOR)
            val queuedKey = keyOf(name, own?.maxQueued != null, "max-queued")
            configCheck(queued >= 0, queuedKey, queued, "Set 0 for no queue, or more.") {
                "A store's query queue is never negative."
            }
            val bulkhead = QueryBulkhead(name, max, wait, queued, maxOf(max, ceiling))
            val options = if (adaptive) global.adaptive.withFloor(floorOf(name, global, own, ceiling)) else null
            return bulkhead to options
        }

        /**
         * The floor of the store [name]'s adaptive bound: its own, else the global one. Above [ceiling] it refuses the
         * start: the bound would never go below the ceiling, so it would not adapt.
         */
        private fun floorOf(name: String, global: QueryStoreSettings, own: QueryStoreLimits?, ceiling: Int): Int {
            val floor = own?.floor ?: global.adaptive.floor
            val key = keyOf(name, own?.floor != null, "adaptive.floor")
            configCheck(floor >= 1, key, floor, "Set 1 or more.") { "A store's query limit falls to 1 at the lowest." }
            configCheck(
                floor <= ceiling,
                key,
                floor,
                "Set a floor of $ceiling or less, or raise wow.stores.$name.query.adaptive.ceiling.",
            ) {
                "The adaptive query bound of the store $name goes up to $ceiling, below its floor $floor: it would " +
                    "never be lowered under write pressure."
            }
            return floor
        }

        /** The ceiling of the store [name]'s adaptive bound: its own, else the global one, else its pool's. */
        private fun ceilingOf(
            name: String,
            pool: StorePool?,
            global: QueryStoreSettings,
            own: QueryStoreLimits?,
        ): Int = (own?.ceiling ?: global.adaptiveCeiling ?: StoreQueryBounds.ceiling(pool)).also {
            val key = keyOf(name, own?.ceiling != null, "adaptive.ceiling")
            configCheck(it >= 1, key, it, "Set 1 or more.") { "$key is $it." }
        }

        /**
         * The key of the store [name]'s query setting [leaf]: the store's own when it sets it ([own]), else the global
         * one, which the store takes.
         */
        private fun keyOf(name: String, own: Boolean, leaf: String): String =
            if (own) "wow.stores.$name.query.$leaf" else "wow.query.$leaf"

        private fun AdaptiveLimitOptions.withFloor(floor: Int): AdaptiveLimitOptions =
            AdaptiveLimitOptions(floor, interval, latencyFactor, minRise, connectionWaits, periods)
    }
}

/**
 * What [QueryStores.of] bounds the queries by: `wow.query.max-concurrent` ([maxConcurrent], null to let each store's
 * provider derive it), `max-wait` and `max-queued`, the adaptive bounds' options ([adaptive], its floor
 * `wow.query.adaptive.floor`, the others constants; [adaptiveCeiling]), each store's own (`wow.stores.<name>.query.*`,
 * [stores]) and the dataset queries' ([datasets]).
 */
@InternalWowApi
class QueryStoreSettings(
    val maxConcurrent: Int? = null,
    val maxWait: Duration = QueryBulkhead.DEFAULT_MAX_WAIT,
    val maxQueued: Int? = null,
    val adaptive: AdaptiveLimitOptions = AdaptiveLimitOptions(),
    val adaptiveCeiling: Int? = null,
    val stores: Map<String, QueryStoreLimits> = emptyMap(),
    val datasets: DatasetQueryLimits = DatasetQueryLimits(),
)

/** One store's bounds (`wow.stores.<name>.query.*`), each null when the store sets none and the global one holds. */
@InternalWowApi
class QueryStoreLimits(
    val maxConcurrent: Int? = null,
    val maxWait: Duration? = null,
    val maxQueued: Int? = null,
    val floor: Int? = null,
    val ceiling: Int? = null,
)

/**
 * The dataset queries' bound (`wow.query.dataset.*`): [maxConcurrent] at once, `0` for none, waiting at most [maxWait]
 * in a queue of at most [maxQueued], each the global one when null.
 */
@InternalWowApi
class DatasetQueryLimits(
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    val maxWait: Duration? = null,
    val maxQueued: Int? = null,
) {
    @InternalWowApi
    companion object {
        /** How many dataset queries a node runs at once by default. */
        const val DEFAULT_MAX_CONCURRENT: Int = 8
    }
}
