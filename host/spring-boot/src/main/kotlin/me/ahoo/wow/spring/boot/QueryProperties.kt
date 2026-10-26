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
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.query.QueryStreams
import me.ahoo.wow.query.AbacQueryOptions
import me.ahoo.wow.query.AdaptiveLimitOptions
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryBulkhead
import me.ahoo.wow.query.QueryCountCacheOptions
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.query.index.QueryIndexDdl
import me.ahoo.wow.query.schema.QuerySensitivityPolicy
import me.ahoo.wow.query.store.DatasetQueryLimits
import me.ahoo.wow.query.store.QueryStoreLimits
import me.ahoo.wow.query.store.QueryStoreSettings
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.runtime.state.KernelStateReads
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * The query plane's configuration, `wow.query.*`, read by [QueryAutoConfiguration]: the query routes and gateways,
 * the budgets they run under, who may query what, and how many queries each store runs at once.
 */
@InternalWowApi
@ConfigurationProperties(prefix = QueryProperties.PREFIX)
class QueryProperties {
    /** Whether the query routes and gateways are wired at all. */
    var enabled: Boolean = true

    /** The budget every query route runs under ([QueryBudget]); a limit of `0` is off. */
    var http: Http = Http()

    /**
     * The budget of in-process queries, the typed [me.ahoo.wow.api.read.QueryApi]'s, trusted code's too: the same keys
     * and defaults as `wow.query.http.*`.
     */
    var inProcess: InProcess = InProcess()

    /**
     * The timeout of an in-process query that has no budget, trusted code's that dropped it
     * (`QueryApi.trusted().unbounded()`); `0` for none.
     */
    var inProcessTimeout: Duration = QueryBudget.DEFAULT_TIMEOUT.toJavaDuration()

    /**
     * Whether a query must say whether it comes over HTTP or in process; off, a query that does not say is taken as
     * in-process.
     */
    var requireExplicitEntry: Boolean = false

    /**
     * Whether an HTTP query of a built-in model is refused unless the caller's authenticated scope pins its tenant;
     * off, a declared scope, such as a tenant header, is trusted.
     */
    var requireAuthenticatedScope: Boolean = false

    /** Queries across tenants on the query routes. */
    var crossTenant: CrossTenant = CrossTenant()

    /** Attribute-based access control of the queries: the caller's tags matched against the snapshots' `tags`. */
    var abac: Abac = Abac()

    /** What the queries may do with sensitive fields. */
    var sensitivity: Sensitivity = Sensitivity()

    /** The indexes of each aggregate's `x-wow-index`. */
    var index: Index = Index()

    /** The datasets, and the bound of their queries. */
    var dataset: Dataset = Dataset()

    /** The cache of the snapshot counts. */
    var countCache: CountCache = CountCache()

    /** Queries of the snapshots and logs kept in memory. */
    var memory: Memory = Memory()

    /**
     * The `events` model, served from each aggregate's log store, or the store its
     * `wow.aggregates.[<context>.<aggregate>].query.events.store` names.
     */
    var events: Events = Events()

    /** The reads of an aggregate's state at a version or a point in time. */
    var state: State = State()

    /**
     * How many snapshot and `events` queries each store runs at once, whichever entry asks; beyond it a query waits,
     * then is `wow.query.busy` (429, with `Retry-After`). Not set, each store's provider derives one from its pool:
     * PostgreSQL a quarter of a pool the appends share, at least 2, or all of a `query-data-source` of its own, 16
     * otherwise. `0` for no bound; `wow.stores.<name>.query.max-concurrent` overrides it for one store.
     */
    var maxConcurrent: Int? = null

    /**
     * How long a query beyond `max-concurrent` waits in a FIFO queue for a permit before it is refused;
     * `wow.stores.<name>.query.max-wait` overrides it for one store.
     */
    var maxWait: Duration = QueryBulkhead.DEFAULT_MAX_WAIT.toJavaDuration()

    /**
     * How many queries may wait for a permit before the next one is refused at once; 4 per permit when not set.
     * `wow.stores.<name>.query.max-queued` overrides it for one store.
     */
    var maxQueued: Int? = null

    /**
     * How the bound of a store adapts to its write pressure, when neither `wow.query.max-concurrent` nor
     * `wow.stores.<name>.query.max-concurrent` fixes it.
     */
    var adaptive: Adaptive = Adaptive()

    /** How a `list` streamed as server-sent events reads its chunks and how long it runs. */
    var stream: Stream = Stream()

    /** The entry policy these settings give every gateway. */
    fun entryPolicy(): QueryEntryPolicy = QueryEntryPolicy(
        requireExplicitEntry = requireExplicitEntry,
        requireAuthenticatedScope = requireAuthenticatedScope,
        http = http.budget(),
        inProcess = inProcess.budget(),
        inProcessTimeout = inProcessTimeout.takeUnless { it.isZero }?.toKotlinDuration(),
    )

    /**
     * A query entry's budget ([QueryBudget]) and the limit of a list that names none, under [label]. A limit of `0` is
     * off.
     */
    open class Budget internal constructor(private val label: String) {
        /** The most results a list or an aggregation may ask for. */
        var maxListSize: Int = DEFAULT.maxListSize

        /** The largest page of a paged or cursor query. */
        var maxPageSize: Int = DEFAULT.maxPageSize

        /** How deep a paged query may go: the most of its page index times its page size. */
        var maxPageWindow: Long = DEFAULT.maxPageWindow

        /** The most nodes a query's filter may have, the caller's scope included. */
        var maxFilterNodes: Int = DEFAULT.maxFilterNodes

        /** The most values one node of a query's filter may list, such as those of an `in`. */
        var maxFilterValues: Int = DEFAULT.maxFilterValues

        /**
         * Whether a query may use the expensive operators, those an index serves poorly, such as a negation or a
         * suffix match; off also refuses a counting query without a filter.
         */
        var allowExpensiveOperators: Boolean = DEFAULT.allowExpensiveOperators

        /**
         * The most groups an aggregation may read when the storage cannot run its HAVING or metric sort itself and
         * the groups are computed in the node.
         */
        var maxResidualGroups: Int = DEFAULT.maxResidualGroups

        /** The default and the largest limit of a count, and the bound of a paged query's total. */
        var countLimit: Int = DEFAULT.countLimit

        /** Whether a paged query may ask for an exact total, beyond `count-limit`. */
        var allowExactCount: Boolean = DEFAULT.allowExactCount

        /** The execution timeout of every query of the entry; `0` runs without one. */
        var timeout: Duration = DEFAULT.timeout.toJavaDuration()

        /**
         * Whether a sort the storage runs without an index counts as an expensive operator, so that
         * `allow-expensive-operators: false` refuses it too.
         */
        var unindexedSortExpensive: Boolean = DEFAULT.unindexedSortExpensive

        /** The limit of a list that names none, at most `max-list-size`. */
        var defaultListSize: Int = HttpQueries.DEFAULT_LIST_SIZE

        fun budget(): QueryBudget = QueryBudget(
            label = label,
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

    /** The budget of the query routes, `wow.query.http.*`. */
    @InternalWowApi
    class Http : Budget(QueryBudget.HTTP_LABEL)

    /** The budget of the in-process [me.ahoo.wow.api.read.QueryApi], `wow.query.in-process.*`: the HTTP defaults. */
    @InternalWowApi
    class InProcess : Budget(QueryBudget.IN_PROCESS_LABEL)

    /** Queries across tenants on the query routes. */
    @InternalWowApi
    class CrossTenant {
        /**
         * Whether a caller that may act across scopes may query across tenants on the query routes
         * (`Wow-Scope: tenant=*` or `tenant=("t1" "t2")`); 403 `wow.auth.forbidden` while off. A type whose tenant is
         * fixed is still read in that tenant alone, and each such query is logged at INFO
         * (`me.ahoo.wow.query.CrossTenantAudit`). The commands and the state reads never cross tenants.
         */
        var enabled: Boolean = false
    }

    /** Attribute-based access control of the queries. */
    @InternalWowApi
    class Abac {
        /** Whether the queries are filtered by the caller's ABAC tags; off, nothing is restricted. */
        var enabled: Boolean = AbacQueryOptions.DEFAULT.enabled

        /**
         * Whether an HTTP query whose caller has no ABAC tags is refused, instead of matching every resource;
         * in-process queries are never refused for this.
         */
        var requirePrincipalTags: Boolean = AbacQueryOptions.DEFAULT.requirePrincipalTags

        /**
         * Whether a resource that lacks one of the caller's tag keys, or has it empty, matches as a public resource.
         */
        var matchMissingTagKey: Boolean = AbacQueryOptions.DEFAULT.matchMissingTagKey

        /** The prefix of the caller attributes that are ABAC tags: the attribute `abac.region` is the tag `region`. */
        var attributePrefix: String = AbacQueryOptions.DEFAULT_ATTRIBUTE_PREFIX

        fun options(): AbacQueryOptions =
            AbacQueryOptions(enabled, requirePrincipalTags, matchMissingTagKey, attributePrefix)
    }

    /** What the queries may do with sensitive fields. */
    @InternalWowApi
    class Sensitivity {
        /** Whether filters and sorts may compare a field whose sensitivity is `DISPLAY`. */
        var displayComparable: Boolean = QuerySensitivityPolicy.DEFAULT.displayComparable

        fun policy(): QuerySensitivityPolicy = QuerySensitivityPolicy(displayComparable)
    }

    /** The query indexes of the snapshots. */
    @InternalWowApi
    class Index {
        /**
         * How the snapshot backend manages the indexes of each aggregate's `x-wow-index` at startup: `auto` creates
         * what is missing, `validate` refuses to start when one is missing, `none` does nothing.
         */
        var ddl: QueryIndexDdl = QueryIndexDdl.AUTO
    }

    /**
     * The cache of the snapshot counts, valid while the type's snapshot write checkpoint holds and for at most `ttl`.
     */
    @InternalWowApi
    class CountCache {
        /** Whether the snapshot counts are cached. */
        var enabled: Boolean = true

        /**
         * Whether the events' counts are cached too; the log runs ahead of the snapshot checkpoint, so a cached events
         * count may lag the streams for up to `ttl`.
         */
        var eventsEnabled: Boolean = false

        /**
         * Whether the totals of paged queries come from the cache too; off, each total is counted with its page, so
         * that it agrees with its rows.
         */
        var pagedTotals: Boolean = false

        /** The most counts the cache holds. */
        var maxEntries: Int = QueryCountCacheOptions.DEFAULT_MAX_ENTRIES

        /**
         * How long a count is cached at most; another node's write can make a cached count lag the rows by up to it.
         */
        var ttl: Duration = QueryCountCacheOptions.DEFAULT_TTL.toJavaDuration()

        fun options(): QueryCountCacheOptions = QueryCountCacheOptions(maxEntries, ttl.toKotlinDuration(), pagedTotals)
    }

    /**
     * The settings the queries of a store whose own settings are [store] are opened with
     * ([me.ahoo.wow.query.store.StoreQueryProvider]): its read preference over `events.mongo-read-preference`.
     */
    internal fun storeQueryOptions(store: WowProperties.Store? = null): StoreQueryOptions {
        val preference = store?.query?.readPreference?.takeIf { it.isNotBlank() }
        return StoreQueryOptions(index.ddl, preference ?: events.mongoReadPreference, preference)
    }

    /** Queries of the snapshots and logs kept in memory. */
    @InternalWowApi
    class Memory {
        /**
         * Whether the snapshots on a store in memory are queried, from the in-memory query backend, and the events of
         * a log in memory scanned: for local runs, demos and tests, not for production.
         */
        var enabled: Boolean = false
    }

    /**
     * How a store's query bound adapts to its write pressure: pressing periods in a row halve it, down to `floor`; a
     * calm period in which a query waited or was refused raises it by one, up to `ceiling`.
     * `wow.stores.<name>.query.adaptive.*` overrides `enabled`, `floor` and `ceiling` for one store.
     */
    @InternalWowApi
    class Adaptive {
        /** Whether a store's query bound adapts to its write pressure. */
        var enabled: Boolean = true

        /** The lowest bound the adaptation halves down to. */
        var floor: Int = AdaptiveLimitOptions.DEFAULT_FLOOR

        /**
         * The highest bound the adaptation raises up to; not set, the provider's: PostgreSQL half a pool the appends
         * share, or all of a `query-data-source`'s.
         */
        var ceiling: Int? = null

        /** The length of one period over which the write pressure is evaluated. */
        var interval: Duration = AdaptiveLimitOptions.DEFAULT_INTERVAL.toJavaDuration()

        /** A period presses when the appends' p90 latency is above this many times its baseline. */
        var latencyFactor: Double = AdaptiveLimitOptions.DEFAULT_LATENCY_FACTOR

        /** The p90 append latency below which a period never presses, so the noise of fast appends is no pressure. */
        var minLatency: Duration = AdaptiveLimitOptions.DEFAULT_MIN_LATENCY.toJavaDuration()

        /** A period presses when at least this many callers wait for a connection of the store's pool. */
        var connectionWaits: Int = AdaptiveLimitOptions.DEFAULT_CONNECTION_WAITS

        /** How many pressing periods in a row halve the bound. */
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

    /**
     * How a `list` streamed as server-sent events reads its chunks and how long it runs. Neither
     * `http.max-list-size` nor `http.default-list-size` bounds such a stream. A list whose sort cannot order a cursor
     * streams without positions, under those bounds, and cannot be resumed.
     */
    @InternalWowApi
    class Stream {
        /** The records of one keyset chunk; each chunk takes a query permit. */
        var chunkSize: Int = QueryGateway.DEFAULT_STREAM_CHUNK_SIZE

        /**
         * How long a stream runs before it ends with `{"reason":"hold-expired","resumable":true}`, for the client to
         * resume it with `Last-Event-ID`; `0` for no bound.
         */
        var maxHold: Duration = QueryStreams.DEFAULT_MAX_HOLD.toJavaDuration()

        internal fun streams(): QueryStreams = QueryStreams(chunkSize, maxHold.toKotlinDuration())
    }

    /** The `events` model's queries. */
    @InternalWowApi
    class Events {
        /**
         * The MongoDB read preference of the `events` queries (`secondaryPreferred`, say), so a free filter scans a
         * secondary rather than the primary the log appends to; the primary when not set.
         * `wow.stores.<name>.query.read-preference` overrides it for one store.
         */
        var mongoReadPreference: String? = null

        /**
         * Whether an `events` query that names no `aggregateId` runs: `allow` or `refuse`; refused, it is
         * `wow.query.invalid`. `wow.stores.<name>.query.events.unpinned` and
         * `wow.aggregates.[<context>.<aggregate>].query.events.unpinned` override it.
         */
        var unpinned: UnpinnedEvents = UnpinnedEvents.ALLOW
    }

    /** The reads of an aggregate's state at a version or a point in time. */
    @InternalWowApi
    class State {
        /**
         * The most commits a state read at a version or an instant replays from the aggregate's snapshot, or from
         * version 1; a state further away is 400 `wow.query.invalid` (`REPLAY_TOO_LONG`).
         */
        var maxReplay: Int = KernelStateReads.DEFAULT_MAX_REPLAY

        /**
         * How long a read of `?minVersion=` waits for the aggregate to reach that version; past it the read is 409
         * `wow.query.min-version-not-reached` with the current `version`.
         */
        var minVersionWait: Duration = KernelStateReads.DEFAULT_MIN_VERSION_WAIT.toJavaDuration()
    }

    /** The datasets, and the bound of their queries. */
    @InternalWowApi
    class Dataset {
        /**
         * The working directory whose `wow/dataset/` holds dataset files, `{context}.{dataset}.json`; each takes
         * precedence over the class path's `META-INF/wow/dataset/` file of the same dataset.
         */
        var configDirectory: String = HostDatasets.DEFAULT_CONFIG_DIRECTORY

        /**
         * How many dataset queries the node runs at once, so analytics queries do not overload a warehouse the Sink
         * writes to; `0` for no bound. The bound is fixed: the warehouse's writes give the node no pressure to adapt
         * to.
         */
        var maxConcurrent: Int = DEFAULT_DATASET_MAX_CONCURRENT

        /** How long a dataset query beyond `max-concurrent` waits for a permit; `wow.query.max-wait` when not set. */
        var maxWait: Duration? = null

        /**
         * How many dataset queries may wait for a permit before the next one is refused; `wow.query.max-queued` when
         * not set.
         */
        var maxQueued: Int? = null
    }

    @InternalWowApi

    companion object {
        const val PREFIX: String = "wow.query"

        /** How many dataset queries a node runs at once by default. */
        const val DEFAULT_DATASET_MAX_CONCURRENT: Int = DatasetQueryLimits.DEFAULT_MAX_CONCURRENT
    }
}

/** The settings of `wow-query`'s query bounds (review R5.C-2): these properties, and each store's of [stores]. */
internal fun QueryProperties.storeSettings(stores: Map<String, WowProperties.Store>): QueryStoreSettings =
    QueryStoreSettings(
        maxConcurrent = maxConcurrent,
        maxWait = maxWait.toKotlinDuration(),
        maxQueued = maxQueued,
        adaptive = adaptive.options(),
        adaptiveEnabled = adaptive.enabled,
        adaptiveCeiling = adaptive.ceiling,
        stores = stores.mapValues { (_, store) ->
            val query = store.query
            QueryStoreLimits(
                maxConcurrent = query.maxConcurrent,
                maxWait = query.maxWait?.toKotlinDuration(),
                maxQueued = query.maxQueued,
                adaptive = query.adaptive.enabled,
                floor = query.adaptive.floor,
                ceiling = query.adaptive.ceiling,
            )
        },
        datasets = DatasetQueryLimits(dataset.maxConcurrent, dataset.maxWait?.toKotlinDuration(), dataset.maxQueued),
    )
