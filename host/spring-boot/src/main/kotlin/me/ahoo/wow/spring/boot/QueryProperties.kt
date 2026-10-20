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

import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.query.AbacQueryOptions
import me.ahoo.wow.query.AdaptiveLimitOptions
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryBulkhead
import me.ahoo.wow.query.QueryCountCacheOptions
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.query.index.QueryIndexDdl
import me.ahoo.wow.query.schema.QuerySensitivityPolicy
import me.ahoo.wow.query.store.StoreQueryOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * The query plane's configuration, `wow.query.*` (decisions D110, D126, D129, D150, D151), read by
 * [QueryAutoConfiguration]:
 *
 * - `wow.query.enabled` (true): whether the query routes and gateways are wired at all.
 * - `wow.query.http.*`: the HTTP budget ([QueryBudget]) every query route runs under: `max-list-size` (1000),
 *   `max-page-size` (100), `max-page-window` (10000), `max-filter-nodes` (128), `max-filter-values` (1000),
 *   `allow-expensive-operators` (true), `max-residual-groups` (10000), `count-limit` (10000; the bound of a count and
 *   of a paged total, D150), `allow-exact-count` (false), `timeout` (30s), `unindexed-sort-expensive` (false); and
 *   `default-list-size` (100), the limit of a list that names none. A limit of `0` is off.
 * - `wow.query.in-process-timeout` (30s): the timeout of an in-process query, which has no budget.
 * - `wow.query.require-explicit-entry`, `wow.query.require-authenticated-scope` (both false): the entry gates.
 * - `wow.query.abac.*` (D129): `enabled` (false), `require-principal-tags` (false), `match-missing-tag-key` (true),
 *   `attribute-prefix` (`abac.`): the caller's `abac.<key>` attributes matched against the snapshots' `tags`.
 * - `wow.query.sensitivity.display-comparable` (true): whether filters and sorts may compare a `DISPLAY` value.
 * - `wow.query.index.ddl` (`auto`, `validate` or `none`; D151): how the snapshot backend manages the indexes of
 *   each aggregate's `x-wow-index` at startup.
 * - `wow.query.count-cache.*` (D152): `enabled` (true), `max-entries` (10000), `ttl` (30s): the cache of the snapshot
 *   counts and paged totals, valid while the type's snapshot write checkpoint holds and for at most `ttl`.
 * - `wow.query.dataset.config-directory` (`config`; D75, D145): the working directory whose `wow/dataset/` holds
 *   dataset files, `{context}.{dataset}.json`; each takes precedence over the classpath's `META-INF/wow/dataset/` file
 *   of the same dataset. The datasets of both are served when a dataset backend is wired (ClickHouse's, say).
 * - `wow.query.memory.enabled` (false; D209): whether the snapshots on a store in memory are queried, from the
 *   in-memory reference query backend, and the events of a log in memory, scanned in memory (review R3.4-1): for
 *   local runs, demos and tests, not for production.
 * - `wow.query.events.*` (D235): the `events` model, served from each aggregate's log store, or the store its
 *   `wow.aggregates.[<context>.<aggregate>].query.events.store` names:
 *   `mongo-read-preference` (none, the primary), the MongoDB read preference of its queries (`secondaryPreferred`,
 *   say), so a free filter scans a secondary rather than the primary the log appends to;
 *   `wow.stores.<name>.query.read-preference` takes its place for one store, and sets that of its snapshot queries
 *   too. `unpinned` (`allow`, or `refuse`; review R3.4-2): whether an events query that names no `aggregateId` runs,
 *   over which `wow.stores.<name>.query.events.unpinned` and `wow.aggregates.[<context>.<aggregate>].query.events
 *   .unpinned` decide for one store or aggregate type; refused, it is `wow.query.invalid` (`STORAGE_UNSUPPORTED` on
 *   `aggregateId`), and the model's descriptor lists the constraint `AGGREGATE_REQUIRED`.
 * - `wow.query.max-concurrent` (none: each store's provider derives one from its pool, PostgreSQL a quarter of a pool
 *   the appends share, at least 2, or all of a `query-data-source` of its own, 16 otherwise; review R3.4-2): how many
 *   snapshot and `events` queries each store runs at once, whichever entry asks, beyond which a query is
 *   `wow.query.busy` (429, with `Retry-After`); `wow.stores.<name>.query.max-concurrent` takes its place for one store;
 *   `0` for no bound. A query beyond the bound waits in a FIFO queue (decision D247) at most `wow.query.max-wait`
 *   (200ms) for a permit, and is refused when the wait runs out or when `wow.query.max-queued` (4 per permit) queries
 *   wait already; `wow.stores.<name>.query.max-wait` and `.max-queued` take their place for one store.
 * - `wow.query.adaptive.*` (decision D252, [Adaptive]): the bound of a store whose `max-concurrent` neither
 *   `wow.query.max-concurrent` nor `wow.stores.<name>.query.max-concurrent` fixes adapts to the store's write pressure,
 *   evaluated every `interval` (1s): `periods` (2) periods in a row in which its appends' p90 latency is above
 *   `latency-factor` (1.5) times its baseline and at least `min-latency` (5ms), or in which `connection-waits` (1)
 *   callers wait for a connection of its pool, halve it down to `floor` (1); a calm period in which a query waited or
 *   was refused raises it by one up to `ceiling` (none: the provider's, PostgreSQL half a pool the appends share, all of
 *   a `query-data-source`'s). `enabled` (true) turns it off; `wow.stores.<name>.query.adaptive.enabled`, `.floor` and
 *   `.ceiling` take their place for one store.
 */
@ConfigurationProperties(prefix = QueryProperties.PREFIX)
class QueryProperties {
    var enabled: Boolean = true
    var http: Http = Http()
    var inProcessTimeout: Duration = QueryBudget.DEFAULT_TIMEOUT.toJavaDuration()
    var requireExplicitEntry: Boolean = false
    var requireAuthenticatedScope: Boolean = false
    var abac: Abac = Abac()
    var sensitivity: Sensitivity = Sensitivity()
    var index: Index = Index()
    var dataset: Dataset = Dataset()
    var countCache: CountCache = CountCache()
    var memory: Memory = Memory()
    var events: Events = Events()
    var maxConcurrent: Int? = null
    var maxWait: Duration = QueryBulkhead.DEFAULT_MAX_WAIT.toJavaDuration()
    var maxQueued: Int? = null
    var adaptive: Adaptive = Adaptive()

    /** The entry policy these settings give every gateway. */
    fun entryPolicy(): QueryEntryPolicy = QueryEntryPolicy(
        requireExplicitEntry = requireExplicitEntry,
        requireAuthenticatedScope = requireAuthenticatedScope,
        http = http.budget(),
        inProcessTimeout = inProcessTimeout.takeUnless { it.isZero }?.toKotlinDuration(),
    )

    class Http {
        var maxListSize: Int = DEFAULT.maxListSize
        var maxPageSize: Int = DEFAULT.maxPageSize
        var maxPageWindow: Long = DEFAULT.maxPageWindow
        var maxFilterNodes: Int = DEFAULT.maxFilterNodes
        var maxFilterValues: Int = DEFAULT.maxFilterValues
        var allowExpensiveOperators: Boolean = DEFAULT.allowExpensiveOperators
        var maxResidualGroups: Int = DEFAULT.maxResidualGroups
        var countLimit: Int = DEFAULT.countLimit
        var allowExactCount: Boolean = DEFAULT.allowExactCount
        var timeout: Duration = DEFAULT.timeout.toJavaDuration()
        var unindexedSortExpensive: Boolean = DEFAULT.unindexedSortExpensive
        var defaultListSize: Int = HttpQueries.DEFAULT_LIST_SIZE

        fun budget(): QueryBudget = QueryBudget(
            label = QueryBudget.HTTP_LABEL,
            maxListSize = maxListSize,
            maxPageSize = maxPageSize,
            maxPageWindow = maxPageWindow,
            maxFilterNodes = maxFilterNodes,
            maxFilterValues = maxFilterValues,
            allowExpensiveOperators = allowExpensiveOperators,
            maxResidualGroups = maxResidualGroups,
            countLimit = countLimit,
            allowExactCount = allowExactCount,
            timeout = timeout.toKotlinDuration(),
            unindexedSortExpensive = unindexedSortExpensive,
        )

        private companion object {
            val DEFAULT = QueryBudget.HTTP_DEFAULT
        }
    }

    class Abac {
        var enabled: Boolean = AbacQueryOptions.DEFAULT.enabled
        var requirePrincipalTags: Boolean = AbacQueryOptions.DEFAULT.requirePrincipalTags
        var matchMissingTagKey: Boolean = AbacQueryOptions.DEFAULT.matchMissingTagKey
        var attributePrefix: String = AbacQueryOptions.DEFAULT_ATTRIBUTE_PREFIX

        fun options(): AbacQueryOptions =
            AbacQueryOptions(enabled, requirePrincipalTags, matchMissingTagKey, attributePrefix)
    }

    class Sensitivity {
        var displayComparable: Boolean = QuerySensitivityPolicy.DEFAULT.displayComparable

        fun policy(): QuerySensitivityPolicy = QuerySensitivityPolicy(displayComparable)
    }

    class Index {
        var ddl: QueryIndexDdl = QueryIndexDdl.AUTO
    }

    class CountCache {
        var enabled: Boolean = true
        var maxEntries: Int = QueryCountCacheOptions.DEFAULT_MAX_ENTRIES
        var ttl: Duration = QueryCountCacheOptions.DEFAULT_TTL.toJavaDuration()

        fun options(): QueryCountCacheOptions = QueryCountCacheOptions(maxEntries, ttl.toKotlinDuration())
    }

    /**
     * The settings the queries of a store whose own settings are [store] are opened with
     * ([me.ahoo.wow.query.store.StoreQueryProvider]): its read preference over `events.mongo-read-preference`.
     */
    internal fun storeQueryOptions(store: WowProperties.Store? = null): StoreQueryOptions {
        val preference = store?.query?.readPreference?.takeIf { it.isNotBlank() }
        return StoreQueryOptions(index.ddl, preference ?: events.mongoReadPreference, preference)
    }

    class Memory {
        var enabled: Boolean = false
    }

    /** `wow.query.adaptive.*` (decision D252): how a store's query bound adapts to its write pressure. */
    class Adaptive {
        var enabled: Boolean = true
        var floor: Int = AdaptiveLimitOptions.DEFAULT_FLOOR
        var ceiling: Int? = null
        var interval: Duration = AdaptiveLimitOptions.DEFAULT_INTERVAL.toJavaDuration()
        var latencyFactor: Double = AdaptiveLimitOptions.DEFAULT_LATENCY_FACTOR
        var minLatency: Duration = AdaptiveLimitOptions.DEFAULT_MIN_LATENCY.toJavaDuration()
        var connectionWaits: Int = AdaptiveLimitOptions.DEFAULT_CONNECTION_WAITS
        var periods: Int = AdaptiveLimitOptions.DEFAULT_PERIODS

        /** The options of a store whose floor is [floor]. */
        internal fun options(floor: Int = this.floor): AdaptiveLimitOptions = AdaptiveLimitOptions(
            floor = floor,
            interval = interval.toKotlinDuration(),
            latencyFactor = latencyFactor,
            minLatency = minLatency.toKotlinDuration(),
            connectionWaits = connectionWaits,
            periods = periods,
        )
    }

    class Events {
        var mongoReadPreference: String? = null
        var unpinned: UnpinnedEvents = UnpinnedEvents.ALLOW
    }

    class Dataset {
        var configDirectory: String = HostDatasets.DEFAULT_CONFIG_DIRECTORY
    }

    companion object {
        const val PREFIX: String = "wow.query"
    }
}
