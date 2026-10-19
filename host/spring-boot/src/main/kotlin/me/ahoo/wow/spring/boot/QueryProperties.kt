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
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryCountCacheOptions
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.index.QueryIndexDdl
import me.ahoo.wow.query.schema.QuerySensitivityPolicy
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
 * - `wow.query.events.*` (D235): the `events` model, served from each aggregate's log store:
 *   `mongo-read-preference` (none, the primary), the MongoDB read preference of its queries (`secondaryPreferred`,
 *   say), so a free filter scans a secondary rather than the primary the log appends to.
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

    class Memory {
        var enabled: Boolean = false
    }

    class Events {
        var mongoReadPreference: String? = null
    }

    class Dataset {
        var configDirectory: String = HostDatasets.DEFAULT_CONFIG_DIRECTORY
    }

    companion object {
        const val PREFIX: String = "wow.query"
    }
}
