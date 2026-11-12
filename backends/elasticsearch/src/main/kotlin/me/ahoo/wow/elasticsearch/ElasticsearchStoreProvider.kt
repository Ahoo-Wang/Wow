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

package me.ahoo.wow.elasticsearch

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.json.jackson.Jackson3JsonpMapper
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.elasticsearch.query.ElasticsearchPool
import me.ahoo.wow.elasticsearch.query.ElasticsearchQueries
import me.ahoo.wow.elasticsearch.query.ElasticsearchStateMappings
import me.ahoo.wow.elasticsearch.snapshot.ElasticsearchSnapshotStore
import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.config.configCheck
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Opened
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StorePool
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore

/**
 * The Elasticsearch stores (`wow.stores.<name>.type: elasticsearch`): snapshots only, never a log,
 * so a store of this type serves `wow.snapshot.stores` and an aggregate's `snapshot.stores` beside a
 * primary that holds the log or snapshots in a database, never as the primary, and never `wow.log.store`. Each store opens over the application's low-level `Rest5Client` that its
 * `client` option names (else the only one), with the settings:
 *
 * - `index-prefix`: a prefix of every index name (`<prefix>_wow_snapshot__<context>__<aggregate>`), for a cluster
 *   several services or environments share; none by default;
 * - `refresh`: when a write becomes visible to searches, `false` (the default: within the index's refresh interval),
 *   `wait_for` (a snapshot is queryable once its write returns) or `true` (an immediate refresh, for tests). A
 *   command's `snapshot` wait is answered by the subscription of its primary snapshot store, never this one's, so
 *   waiting for a refresh serves only an in-process caller that builds a wait hop on this store's subscription in
 *   code (`wow.snapshot:<store>`, a name `Wow-Wait` does not admit), and caps that subscription's writes at a batch
 *   per write lane a refresh interval;
 * - `query-client`: the `Rest5Client` bean the store's queries use, so they draw on a connection pool of their own
 *   instead of the one the snapshot writes use; the store's `client` by default;
 * - `reindex-requests-per-second`: how many documents a second the store indexes again when its queries map a field an
 *   index lacks, so that the documents written before it are found by it too; 1,000 by default, `-1` for no limit.
 *
 * Found by `ServiceLoader`. It is never `available`: Elasticsearch holds no log, so `wow.backend=auto` never takes it.
 */
internal class ElasticsearchStoreProvider : StoreProvider {
    override val type: String = TYPE

    /** An Elasticsearch store is named by a store, never chosen for `wow.backend=auto`. */
    override fun available(config: StoreConfig): Boolean = false

    override val requirement: String = "wow-elasticsearch on the classpath and a Rest5Client bean"

    override val options: List<ProviderOption<*>> =
        listOf(CLIENT, QUERY_CLIENT, INDEX_PREFIX, REFRESH, REINDEX_REQUESTS_PER_SECOND)

    override fun open(config: StoreConfig): Store {
        val restClient = config.resources.resource(
            Rest5Client::class.java,
            config.settings.option(CLIENT),
            "${config.key}.${CLIENT.key}"
        )
        val queryClient = config.settings.option(QUERY_CLIENT)?.let {
            config.resources.resource(Rest5Client::class.java, it, "${config.key}.${QUERY_CLIENT.key}")
        }
        val rewriteRate = config.settings.option(REINDEX_REQUESTS_PER_SECOND)
        configCheck(
            rewriteRate > 0 || rewriteRate == -1,
            "${config.key}.${REINDEX_REQUESTS_PER_SECOND.key}",
            rewriteRate,
            "Set a number of documents a second, or -1 for no limit.",
        ) { "The rate of an Elasticsearch mapping's rewrite cannot be $rewriteRate." }
        return ElasticsearchStore(
            config,
            restClient,
            ElasticsearchNames(config.settings.option(INDEX_PREFIX).orEmpty()),
            config.settings.option(REFRESH).refresh,
            queryRestClient = queryClient?.takeIf { it !== restClient },
            rewriteRate = rewriteRate,
        )
    }

    companion object {
        const val TYPE = "elasticsearch"

        /** The option naming the store's `Rest5Client` bean. */
        val CLIENT = ProviderOption.string(
            "client",
            "the name of the Rest5Client bean the store opens over; the only one when not set.",
        ).perInstance()

        /** The option naming the `Rest5Client` bean of the store's queries, when they have a pool of their own. */
        val QUERY_CLIENT = ProviderOption.string(
            "query-client",
            "the name of the Rest5Client bean the store's queries use, so they draw on a connection pool of their " +
                "own instead of the one the snapshot writes use; the store's client when not set.",
        ).perInstance()

        /** The option of the prefix of the store's index names. */
        val INDEX_PREFIX = ProviderOption.string(
            "index-prefix",
            "a prefix of every index name of the store (<prefix>_wow_snapshot__<context>__<aggregate>), for a " +
                "cluster several services or environments share; none when not set.",
        )

        /** The option of when a write becomes visible to searches. */
        val REFRESH = ProviderOption.enumeration(
            "refresh",
            "when a write becomes visible to searches: none (the default: within the index's refresh interval; a " +
                "command's snapshot wait never covers this store), wait-for (a snapshot is queryable once its write " +
                "returns, which only an in-process caller that waits on the store's own snapshot subscription in " +
                "code can rely on) or immediate (an immediate refresh, for tests).",
            ElasticsearchRefresh::class.java,
            ElasticsearchRefresh.NONE,
        )

        /** The option of how fast an index's documents are indexed again for a field the store's queries map. */
        val REINDEX_REQUESTS_PER_SECOND = ProviderOption.int(
            "reindex-requests-per-second",
            "how many documents a second the store indexes again when its queries map a field an index lacks, so " +
                "that the documents written before it are found by it too; a search of the field misses them until " +
                "the rewrite ends. 1000 by default (a million documents in about 17 minutes); -1 for no limit.",
            ElasticsearchStateMappings.DEFAULT_REWRITE_RATE,
        )
    }
}

/** When a store's write becomes visible to searches (`refresh`): the Elasticsearch [refresh] policy it writes with. */
@InternalWowApi
enum class ElasticsearchRefresh(val refresh: Refresh) {
    /**
     * Once its write returns, the snapshot is queryable: Elasticsearch answers after its next refresh. It serves an
     * in-process caller that builds a wait hop on the store's own snapshot subscription in code; the `Wow-Wait` header
     * does not admit that subscription's name.
     */
    WAIT_FOR(Refresh.WaitFor),

    /** Each write refreshes its shards at once: for tests. */
    IMMEDIATE(Refresh.True),

    /** Within the index's refresh interval: the default. */
    NONE(Refresh.False),
}

/**
 * A store on Elasticsearch over [restClient]: each context's snapshots, one index per aggregate type, in the indices
 * [names] gives; its queries over [queryRestClient] when it has one, else over [restClient] too. It holds no log, so
 * no checkpoints, leases, failure ledger, reservations or timers either: the node keeps those with its log.
 */
internal class ElasticsearchStore(
    config: StoreConfig,
    val restClient: Rest5Client,
    val names: ElasticsearchNames,
    val refresh: Refresh = Refresh.False,
    val queryRestClient: Rest5Client? = null,
    /** How many documents a second a mapping's rewrite indexes again (`reindex-requests-per-second`). */
    val rewriteRate: Int = ElasticsearchStateMappings.DEFAULT_REWRITE_RATE,
) : Store(config) {
    /** The client of Wow's JSON: a state's numbers are read and written exactly. */
    val client: ElasticsearchAsyncClient by lazy { client(restClient) }

    /** The cluster's info, a `GET /`: the store answers. */
    override suspend fun probe() {
        client.info().awaitElasticsearch()
    }

    /** The client of the store's queries: its own pool's when it has one, else [client]. */
    val queryClient: ElasticsearchAsyncClient by lazy { queryRestClient?.let(::client) ?: client }

    /** Whether the queries draw on the connection pool the snapshot writes use. */
    val queriesShareWrites: Boolean get() = queryRestClient == null

    private val pool = ElasticsearchPool(this)
    private val queries = ElasticsearchQueries(this)

    override val location: Any get() = restClient

    /** Its HTTP pool ([ElasticsearchPool]) and its queries ([ElasticsearchQueries]). */
    override fun <F : Any> facet(type: Class<F>): F? = when (type) {
        StorePool::class.java -> type.cast(pool)
        StoreQueries::class.java -> type.cast(queries)
        else -> null
    }

    override val holdsLogs: Boolean get() = false

    /**
     * Never a primary snapshot store: the commands load their state from the primary, and a search index refreshed in
     * near real time, without transactions, holds a copy of the snapshots beside a store that holds the log or
     * snapshots in a database's guarantees.
     */
    override val primarySnapshots: Boolean get() = false

    override fun openSnapshots(context: String): Opened<SnapshotStore> =
        ElasticsearchSnapshotStore(client, names, context, ddl = config.ddl, refresh = refresh)
            .let { Opened.of(it, it::createSchema) }

    override fun openCheckpoints(): Opened<CheckpointStore> = throw noLog()

    override fun openLeases(): Opened<LeaseStore> = throw noLog()

    override fun openLedger(): Opened<FailureLedger> = throw noLog()

    override fun openReservations(): Opened<ReservationStore> = throw noLog()

    override fun openLog(context: String, partitions: Int): Opened<EventLog> = throw noLog()

    override fun openTimers(context: String, partitions: Int): Opened<TimerStore> = throw noLog()

    private fun noLog(): UnsupportedOperationException =
        UnsupportedOperationException(
            "The Elasticsearch store $name holds no log: snapshots only. Keep the log and the control plane " +
                "(timers, checkpoints, leases, the failure ledger, reservations) on a PostgreSQL or MongoDB store " +
                "(wow.log.store), and name $name among a type's snapshot stores " +
                "(wow.aggregates.[<context>.<aggregate>].snapshot.stores).",
        )

    companion object {
        /** An asynchronous client over [restClient] that reads and writes Wow's JSON. */
        fun client(restClient: Rest5Client): ElasticsearchAsyncClient =
            ElasticsearchAsyncClient(Rest5ClientTransport(restClient, Jackson3JsonpMapper(WowJson.mapper)))
    }
}
