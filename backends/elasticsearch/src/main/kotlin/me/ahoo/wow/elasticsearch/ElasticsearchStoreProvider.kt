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
import kotlin.time.Duration

/**
 * The Elasticsearch stores (`wow.stores.<name>.type: elasticsearch`): snapshots only, never a log, so a store of this
 * type is an aggregate type's own snapshot store (`wow.snapshot.store`, `wow.aggregates.[<context>.<aggregate>]
 * .snapshot.store`), which its commands load their state from by id, or the copy its snapshot queries read
 * (`wow.aggregates.[<context>.<aggregate>].query.snapshot.store`) beside its own store; never `wow.log.store`. Each
 * store opens over the application's low-level `Rest5Client` that its `client` option names (else the only one), with
 * the settings:
 *
 * - `index-prefix`: a prefix of every index name (`<prefix>_wow_snapshot__<context>__<aggregate>`), for a cluster
 *   several services or environments share; none by default;
 * - `refresh`: when a write becomes visible to searches, `false` (the default: within the index's refresh interval),
 *   `wait_for` (a snapshot is queryable once its write returns) or `true` (an immediate refresh, for tests). A load by
 *   id reads a write once it returns, whatever `refresh` is. A command's `snapshot` wait is answered by the
 *   subscription of its type's own snapshot store, and its `queryable` wait by that of the store its snapshot queries
 *   read, so with `wait_for` on that store a caller that waits `queryable` then sees its commit in a list or a count.
 *   Waiting for a refresh caps that subscription's writes at four batches in flight a refresh interval;
 * - `query-client`: the `Rest5Client` bean the store's queries use, so they draw on a connection pool of their own
 *   instead of the one the snapshot writes use; the store's `client` by default;
 * - `reindex-requests-per-second`: how many documents a second the store indexes again when its queries map a field an
 *   index lacks, so that the documents written before it are found by it too; 1,000 by default, `-1` for no limit;
 * - `operation-timeout`: how long the caller of each of its requests, writes, loads and queries alike, waits for an
 *   answer ([ElasticsearchTimeouts.OPERATION], 30 seconds, when not set), so that a server that stops answering fails
 *   its callers instead of holding them; the request and its connection are the client's ([BoundedHttpClient]).
 *
 * Found by `ServiceLoader`. It is never `available`: Elasticsearch holds no log, so `wow.backend=auto` never takes it.
 */
internal class ElasticsearchStoreProvider : StoreProvider {
    override val type: String = TYPE

    /** An Elasticsearch store is named by a store, never chosen for `wow.backend=auto`. */
    override fun available(config: StoreConfig): Boolean = false

    override val requirement: String = "wow-elasticsearch on the classpath and a Rest5Client bean"

    override val options: List<ProviderOption<*>> =
        listOf(CLIENT, QUERY_CLIENT, INDEX_PREFIX, REFRESH, REINDEX_REQUESTS_PER_SECOND, OPERATION_TIMEOUT)

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
            operationTimeout = config.settings.option(OPERATION_TIMEOUT),
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
            "when a write becomes visible to searches: none (the default: within the index's refresh interval, " +
                "while a load by id reads it once it returns), wait-for (a snapshot is in a list or a count once its " +
                "write returns, so a command that waits queryable sees its commit there; each write takes up to a " +
                "refresh interval longer) or immediate (an immediate refresh, for tests).",
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

        /** The option bounding each of the store's requests. */
        val OPERATION_TIMEOUT = ProviderOption.duration(
            "operation-timeout",
            "how long the caller of each request of an Elasticsearch store, its writes, loads and queries alike, " +
                "waits for an answer: a server that stops answering fails the caller instead of holding it. The " +
                "request and its connection are the client's, freed when the server answers or at the client's own " +
                "response timeout.",
            ElasticsearchTimeouts.OPERATION,
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
    /** How long each request may wait for an answer (`operation-timeout`, [ElasticsearchTimeouts]). */
    private val operationTimeout: Duration = ElasticsearchTimeouts.OPERATION,
) : Store(config) {
    /** The client of Wow's JSON: a state's numbers are read and written exactly; each request [operationTimeout]. */
    val client: ElasticsearchAsyncClient by lazy { client(restClient, operationTimeout) }

    /** The cluster's info, a `GET /`: the store answers. */
    override suspend fun probe() {
        client.info().awaitElasticsearch()
    }

    /** The client of the store's queries: its own pool's when it has one, else [client]. */
    val queryClient: ElasticsearchAsyncClient by lazy {
        queryRestClient?.let { client(it, operationTimeout) } ?: client
    }

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
                "(wow.log.store), and make $name the store a type's snapshot queries read, which keeps a copy of them " +
                "(wow.aggregates.[<context>.<aggregate>].query.snapshot.store).",
        )

    companion object {
        /** An asynchronous client over [restClient] that reads and writes Wow's JSON, each request within [timeout]. */
        fun client(
            restClient: Rest5Client,
            timeout: Duration = ElasticsearchTimeouts.OPERATION
        ): ElasticsearchAsyncClient =
            ElasticsearchAsyncClient(BoundedTransport(restClient, Jackson3JsonpMapper(WowJson.mapper), timeout))
    }
}
