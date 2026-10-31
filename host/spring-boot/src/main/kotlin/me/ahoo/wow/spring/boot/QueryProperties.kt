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
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.admission.AdaptiveLimitOptions
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.admission.QueryBulkhead
import me.ahoo.wow.query.cache.QueryCountCacheOptions
import me.ahoo.wow.query.events.UnpinnedEvents
import me.ahoo.wow.query.policy.AbacQueryOptions
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.schema.QuerySensitivityPolicy
import me.ahoo.wow.query.store.DatasetQueryLimits
import me.ahoo.wow.query.store.QueryStoreLimits
import me.ahoo.wow.query.store.QueryStoreSettings
import me.ahoo.wow.query.store.StoreQueryOptions
import me.ahoo.wow.runtime.state.KernelStateReads
import me.ahoo.wow.spi.Ddl
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

    /** The budget ([QueryBudget]) each query entry runs under: the query routes' and the in-process queries'. */
    var budgets: Budgets = Budgets()

    /**
     * The timeout of a trusted in-process query that dropped its budget (`NodeQueryApi.trusted().unbounded()`); `0` for
     * none.
     */
    var unboundedTimeout: Duration = QueryBudget.DEFAULT_TIMEOUT.toJavaDuration()

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

    /**
     * Whether a caller that may act across scopes may query across tenants on the query routes
     * (`Wow-Scope: tenant=*` or `tenant=("t1" "t2")`); 403 `wow.auth.forbidden` while not. A type whose tenant is
     * fixed is still read in that tenant alone, and each such query is logged at INFO
     * (`me.ahoo.wow.query.CrossTenantAudit`). The commands and the state reads never cross tenants.
     */
    var allowCrossTenant: Boolean = false

    /** Attribute-based access control of the queries: the caller's tags matched against the snapshots' `tags`. */
    var abac: Abac = Abac()

    /** What the queries may do with sensitive fields. */
    var sensitivity: Sensitivity = Sensitivity()

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
        http = budgets.http.budget(),
        inProcess = budgets.inProcess.budget(),
        inProcessTimeout = unboundedTimeout.takeUnless { it.isZero }?.toKotlinDuration(),
    )

    /** The budget of each query entry. */
    @InternalWowApi
    class Budgets {
        /** The budget every query route runs under; a limit of `0` is off. */
        var http: Http = Http()

        /** The budget of the in-process queries, the typed [me.ahoo.wow.api.read.QueryApi]'s, trusted code's too. */
        var inProcess: InProcess = InProcess()
    }

    /**
     * A query entry's budget ([QueryBudget]) and the limit of a list that names none, from its [defaults]. A limit of
     * `0` is off.
     */
    @InternalWowApi
    open class Budget internal constructor(private val defaults: QueryBudget) {
        /** The most results a list or an aggregation may ask for. */
        var maxListSize: Int = defaults.maxListSize

        /** The largest page of a paged or cursor query. */
        var maxPageSize: Int = defaults.maxPageSize

        /** How deep a paged query may go: the most of its page index times its page size. */
        var maxPageWindow: Long = defaults.maxPageWindow

        /** The most nodes a query's filter may have, the caller's scope included. */
        var maxFilterNodes: Int = defaults.maxFilterNodes

        /** The most values one node of a query's filter may list, such as those of an `in`. */
        var maxFilterValues: Int = defaults.maxFilterValues

        /**
         * Whether a query may use the expensive operators, those an index serves poorly, such as a negation or a
         * suffix match; off also refuses a counting query without a filter.
         */
        var allowExpensiveOperators: Boolean = defaults.allowExpensiveOperators

        /**
         * The most groups an aggregation may read when the storage cannot run its HAVING or metric sort itself and
         * the groups are computed in the node.
         */
        var maxResidualGroups: Int = defaults.maxResidualGroups

        /** The default and the largest limit of a count, and the bound of a paged query's total. */
        var countLimit: Int = defaults.countLimit

        /** Whether a paged query may ask for an exact total, beyond `count-limit`. */
        var allowExactCount: Boolean = defaults.allowExactCount

        /** The execution timeout of every query of the entry; `0` runs without one. */
        var timeout: Duration = defaults.timeout.toJavaDuration()

        /**
         * Whether a sort the storage runs without an index counts as an expensive operator, so that
         * `allow-expensive-operators: false` refuses it too.
         */
        var unindexedSortExpensive: Boolean = defaults.unindexedSortExpensive

        /** The limit of a list that names none, at most `max-list-size`. */
        var defaultListSize: Int = HttpQueries.DEFAULT_LIST_SIZE

        fun budget(): QueryBudget = QueryBudget(
            label = defaults.label,
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
    }

    /** The budget of the query routes, `wow.query.budgets.http.*`. */
    @InternalWowApi
    class Http : Budget(QueryBudget.HTTP_DEFAULT)

    /** The budget of the in-process [me.ahoo.wow.api.read.QueryApi], `wow.query.budgets.in-process.*`. */
    @InternalWowApi
    class InProcess : Budget(QueryBudget.IN_PROCESS_DEFAULT)

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

    /**
     * The cache of the snapshot counts, valid while the type's snapshot write checkpoint holds and for at most `ttl`.
     */
    @InternalWowApi
    class CountCache {
        /** Whether the snapshot counts are cached. */
        var enabled: Boolean = true

        /** The cache of the `events` counts. */
        var events: Events = Events()

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

        /** The cache of the `events` counts. */
        @InternalWowApi
        class Events {
            /**
             * Whether the events' counts are cached too; the log runs ahead of the snapshot checkpoint, so a cached
             * events count may lag the streams for up to `ttl`.
             */
            var enabled: Boolean = false
        }
    }

    /** Queries of the snapshots and logs kept in memory. */
    @InternalWowApi
    class Memory {
        /**
         * Whether the snapshots on a store in memory are queried, from the in-memory query backend, and the events of
         * a log in memory scanned: for local runs, demos and tests, not for production. On by default wherever a store
         * is in memory (`wow.backend: memory`, or `auto` choosing it); `false` turns them off there.
         */
        var enabled: Boolean = true
    }

    /**
     * How a store's query bound adapts to its write pressure, when neither `wow.query.max-concurrent` nor
     * `wow.stores.<name>.query.max-concurrent` fixes it: pressing periods in a row halve it, down to `floor`; a calm
     * period in which a query waited or was refused raises it by one, up to `ceiling`.
     * `wow.stores.<name>.query.adaptive.*` overrides `floor` and `ceiling` for one store.
     */
    @InternalWowApi
    class Adaptive {
        /** The lowest bound the adaptation halves down to. */
        var floor: Int = AdaptiveLimitOptions.DEFAULT_FLOOR

        /**
         * The highest bound the adaptation raises up to; not set, the provider's: PostgreSQL half a pool the appends
         * share, or all of a `query-data-source`'s.
         */
        var ceiling: Int? = null

        /** The options of a store whose floor is [floor]; the period and the pressure's thresholds are constants. */
        internal fun options(floor: Int = this.floor): AdaptiveLimitOptions = AdaptiveLimitOptions(floor = floor)
    }

    /**
     * How a `list` streamed as server-sent events reads its chunks and how long it runs. Neither
     * `budgets.http.max-list-size` nor `budgets.http.default-list-size` bounds such a stream. A list whose sort cannot
     * order a cursor streams without positions, under those bounds, and cannot be resumed.
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
         * The most commits a state read at a version or an instant, or a trace, replays from the aggregate's
         * snapshot, or from version 1: the snapshot holds only the latest state, so an older one is replayed from the
         * first version. A state further away is 400 `wow.query.invalid` (`replay-too-long`). It keeps one read from
         * holding the node; raise it where states that far back are read. An aggregate's events are not bounded by
         * it: the events query reads them page by page.
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

/** The settings of `wow-query`'s query bounds: these properties, and each store's of [stores]. */
internal fun QueryProperties.storeSettings(stores: Map<String, WowProperties.Store>): QueryStoreSettings =
    QueryStoreSettings(
        maxConcurrent = maxConcurrent,
        maxWait = maxWait.toKotlinDuration(),
        maxQueued = maxQueued,
        adaptive = adaptive.options(),
        adaptiveCeiling = adaptive.ceiling,
        stores = stores.mapValues { (_, store) ->
            val query = store.query
            QueryStoreLimits(
                maxConcurrent = query.maxConcurrent,
                maxWait = query.maxWait?.toKotlinDuration(),
                maxQueued = query.maxQueued,
                floor = query.adaptive.floor,
                ceiling = query.adaptive.ceiling,
            )
        },
        datasets = DatasetQueryLimits(dataset.maxConcurrent, dataset.maxWait?.toKotlinDuration(), dataset.maxQueued),
    )

/**
 * The settings the queries of the store [name] are opened with ([me.ahoo.wow.query.store.StoreQueries]): the
 * index `ddl` of the store (`wow.stores.<name>.ddl`, else `wow.ddl`). A backend's own query settings, such as
 * MongoDB's read preference, are options of its store.
 */
internal fun WowProperties?.storeQueryOptions(name: String): StoreQueryOptions =
    StoreQueryOptions(this?.stores?.get(name)?.ddl ?: this?.ddl ?: Ddl.AUTO)
